package dev.platform.reading.tuvi.adapter.out.knowledge;

import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports {@code *.jsonl} knowledge files. Every file is parsed and validated first; the database
 * is then updated in one transaction, so an invalid file changes nothing.
 */
@Component
public class JsonlKnowledgeImporter {

  private static final Set<String> FIELDS =
      Set.of("key", "school", "lang", "topic", "content", "source_ref", "status");
  private static final Set<String> TOPICS =
      Set.of("tinh-cach", "tinh-duyen", "su-nghiep", "tai-loc", "suc-khoe", "van-han");
  private static final Set<String> STATUSES = Set.of("draft", "approved", "hidden");

  private record Record(
      String key,
      String school,
      String lang,
      String topic,
      String content,
      String sourceRef,
      String status) {

    String sha256() {
      try {
        byte[] d =
            MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private record Existing(
      long id, int version, String sha, String topic, String sourceRef, String status) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public JsonlKnowledgeImporter(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txManager);
  }

  public ImportResult importDir(Path dir) {
    List<Record> records = parseAll(dir);
    return tx.execute(status -> apply(records));
  }

  private ImportResult apply(List<Record> records) {
    Map<String, Existing> existing = new HashMap<>();
    jdbc.query(
        "select id, key, school, lang, version, content_sha256, topic, source_ref, status"
            + " from knowledge",
        rs -> {
          existing.put(
              identity(rs.getString("key"), rs.getString("school"), rs.getString("lang")),
              new Existing(
                  rs.getLong("id"),
                  rs.getInt("version"),
                  rs.getString("content_sha256"),
                  rs.getString("topic"),
                  rs.getString("source_ref"),
                  rs.getString("status")));
        });

    int inserted = 0;
    int updated = 0;
    int unchanged = 0;
    for (Record r : records) {
      Existing e = existing.get(identity(r.key, r.school, r.lang));
      String sha = r.sha256();
      if (e == null) {
        jdbc.update(
            "insert into knowledge (key, school, lang, topic, content, source_ref, status,"
                + " version, content_sha256) values (?, ?, ?, ?, ?, ?, ?, 1, ?)",
            r.key,
            r.school,
            r.lang,
            r.topic,
            r.content,
            r.sourceRef,
            r.status,
            sha);
        inserted++;
      } else if (sameAs(e, r, sha)) {
        unchanged++;
      } else {
        jdbc.update(
            "update knowledge set topic = ?, content = ?, source_ref = ?, status = ?,"
                + " content_sha256 = ?, version = ?, updated_at = now() where id = ?",
            r.topic,
            r.content,
            r.sourceRef,
            r.status,
            sha,
            e.version + 1,
            e.id);
        updated++;
      }
    }
    if (inserted + updated > 0) {
      jdbc.update("update knowledge_meta set version = version + 1 where id = 1");
    }
    return new ImportResult(inserted, updated, unchanged);
  }

  private static boolean sameAs(Existing e, Record r, String sha) {
    return e.sha.equals(sha)
        && java.util.Objects.equals(e.topic, r.topic)
        && e.sourceRef.equals(r.sourceRef)
        && e.status.equals(r.status);
  }

  private static String identity(String key, String school, String lang) {
    return key + "|" + school + "|" + lang;
  }

  private List<Record> parseAll(Path dir) {
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
    } catch (IOException e) {
      throw new KnowledgeImportException("cannot read knowledge dir " + dir, e);
    }
    List<Record> out = new ArrayList<>();
    Map<String, String> seen = new LinkedHashMap<>();
    for (Path file : files) {
      List<String> lines;
      try {
        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new KnowledgeImportException("cannot read " + file, e);
      }
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).isBlank()) {
          continue;
        }
        String where = file + ":" + (i + 1);
        Record r = parseLine(lines.get(i), where);
        String id = identity(r.key, r.school, r.lang);
        String first = seen.putIfAbsent(id, where);
        if (first != null) {
          throw new KnowledgeImportException(where + ": duplicate of " + first);
        }
        out.add(r);
      }
    }
    return out;
  }

  private static Record parseLine(String line, String where) {
    JsonNode n;
    try {
      n = JsonMapper.shared().readTree(line);
    } catch (RuntimeException e) {
      throw new KnowledgeImportException(where + ": not valid JSON", e);
    }
    if (n == null || !n.isObject()) {
      throw new KnowledgeImportException(where + ": record must be a JSON object");
    }
    for (String f : n.propertyNames()) {
      if (!FIELDS.contains(f)) {
        throw new KnowledgeImportException(where + ": unknown field " + f);
      }
    }
    String key = text(n, "key", where, true);
    try {
      new KnowledgeKey(key);
    } catch (IllegalArgumentException e) {
      throw new KnowledgeImportException(where + ": invalid knowledge key", e);
    }
    String school = text(n, "school", where, true);
    String lang = text(n, "lang", where, true);
    if (!"vi".equals(lang)) {
      throw new KnowledgeImportException(where + ": lang must be vi");
    }
    String topic = text(n, "topic", where, false);
    if (topic != null && !TOPICS.contains(topic)) {
      throw new KnowledgeImportException(where + ": invalid topic");
    }
    String content = text(n, "content", where, true);
    String sourceRef = text(n, "source_ref", where, true);
    String status = text(n, "status", where, true);
    if (!STATUSES.contains(status)) {
      throw new KnowledgeImportException(where + ": invalid status");
    }
    return new Record(key, school, lang, topic, content, sourceRef, status);
  }

  private static String text(JsonNode n, String field, String where, boolean required) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      if (required) {
        throw new KnowledgeImportException(where + ": missing " + field);
      }
      return null;
    }
    if (!v.isString() || v.stringValue().isBlank()) {
      throw new KnowledgeImportException(where + ": " + field + " must be a non-empty string");
    }
    return v.stringValue();
  }
}
