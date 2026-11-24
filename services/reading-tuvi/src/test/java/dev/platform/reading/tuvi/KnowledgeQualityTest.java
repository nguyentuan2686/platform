package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Quality gate over the doctrine knowledge files (cwd is the module directory). */
class KnowledgeQualityTest {

  private static final Path BAD = Path.of("src", "test", "resources", "knowledge-bad");
  private static final Path DOCTRINE = Path.of("..", "..", "knowledge", "tuvi", "doctrine");

  private static List<JsonNode> read(Path file) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (!line.isBlank()) {
        out.add(JsonMapper.shared().readTree(line));
      }
    }
    return out;
  }

  @Test
  void clean_fixture_has_no_violations() throws IOException {
    assertThat(QualityRules.checkAll(read(BAD.resolve("clean.jsonl")))).isEmpty();
  }

  @Test
  void each_bad_fixture_reports_its_rule_and_the_key_but_not_the_content() throws IOException {
    List<String> rules =
        List.of(
            "schema",
            "school",
            "lang",
            "sentences",
            "length",
            "diacritic",
            "cjk",
            "banned",
            "voice",
            "source_ref",
            "topic",
            "duplicate-content",
            "p2-parent");
    for (String rule : rules) {
      List<JsonNode> records = read(BAD.resolve(rule + ".jsonl"));
      List<String> violations = QualityRules.checkAll(records);
      assertThat(violations).as(rule).anyMatch(v -> v.startsWith("rule=" + rule + " "));
      assertThat(violations).as(rule).allMatch(v -> v.contains("key=tuvi:"));
      for (JsonNode r : records) {
        String content = r.get("content").stringValue();
        assertThat(violations).as(rule).noneMatch(v -> v.contains(content));
      }
    }
  }

  @Test
  void each_bad_fixture_trips_only_its_own_rule() throws IOException {
    // topic/cjk/etc. fixtures are built from the clean record, so no other rule may fire
    for (String rule : List.of("school", "sentences", "diacritic", "cjk", "voice")) {
      List<String> violations = QualityRules.checkAll(read(BAD.resolve(rule + ".jsonl")));
      assertThat(violations).as(rule).allMatch(v -> v.startsWith("rule=" + rule + " "));
    }
  }

  @Test
  void every_doctrine_record_passes_the_gate() throws IOException {
    if (!Files.isDirectory(DOCTRINE)) {
      return;
    }
    List<JsonNode> all = new ArrayList<>();
    try (Stream<Path> s = Files.walk(DOCTRINE)) {
      for (Path f :
          s.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
              .sorted()
              .toList()) {
        all.addAll(read(f));
      }
    }
    assertThat(QualityRules.checkAll(all)).isEmpty();
  }
}
