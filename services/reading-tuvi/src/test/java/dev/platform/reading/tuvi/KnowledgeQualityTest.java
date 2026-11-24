package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Quality gate over the doctrine knowledge files (cwd is the module directory). */
class KnowledgeQualityTest {

  private static final Path BAD = Path.of("src", "test", "resources", "knowledge-bad");
  private static final Path KNOWLEDGE = Path.of("..", "..", "knowledge", "tuvi");
  private static final Path SOURCES =
      Path.of("..", "..", "knowledge", "sources", "tuvi", "ziwei-quanshu");

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
  void cjk_punctuation_fullwidth_forms_and_supplementary_ideographs_are_rejected()
      throws IOException {
    for (String fixture : List.of("cjk-punct", "cjk-fullwidth", "cjk-supplementary")) {
      List<String> violations = QualityRules.checkAll(read(BAD.resolve(fixture + ".jsonl")));
      assertThat(violations).as(fixture).isNotEmpty();
      assertThat(violations).as(fixture).allMatch(v -> v.startsWith("rule=cjk "));
    }
  }

  @Test
  void every_derived_source_ref_part_is_validated() throws IOException {
    List<String> violations = QualityRules.checkAll(read(BAD.resolve("source_ref-second.jsonl")));
    assertThat(violations).containsExactly("rule=source_ref key=tuvi:star=TuVi:palace=Menh");
  }

  @Test
  void a_cited_heading_missing_from_the_source_text_is_reported() throws IOException {
    assertThat(QualityRules.missingHeadings(read(BAD.resolve("clean.jsonl")), SOURCES)).isEmpty();
    assertThat(QualityRules.missingHeadings(read(BAD.resolve("source_heading.jsonl")), SOURCES))
        .containsExactly("rule=source_heading key=tuvi:star=TuVi:palace=Menh");
  }

  @Test
  void gated_files_are_every_knowledge_jsonl_except_frames_and_daily() throws IOException {
    Path tree = Path.of("src", "test", "resources", "knowledge-tree");
    assertThat(
            gatedFiles(tree).stream()
                .map(p -> tree.relativize(p).toString().replace(java.io.File.separatorChar, '/')))
        .containsExactly("doctrine/a.vi.jsonl", "extra/b.vi.jsonl", "top.vi.jsonl");
  }

  @Test
  void every_knowledge_record_passes_the_gate() throws IOException {
    if (!Files.isDirectory(KNOWLEDGE)) {
      return;
    }
    List<JsonNode> all = new ArrayList<>();
    for (Path f : gatedFiles(KNOWLEDGE)) {
      all.addAll(read(f));
    }
    assertThat(all).isNotEmpty();
    assertThat(QualityRules.checkAll(all)).isEmpty();
    assertThat(QualityRules.missingHeadings(all, SOURCES)).isEmpty();
  }

  @Test
  void p2_files_rarely_open_with_neu() throws IOException {
    if (!Files.isDirectory(KNOWLEDGE.resolve("doctrine"))) {
      return;
    }
    try (Stream<Path> s = Files.list(KNOWLEDGE.resolve("doctrine"))) {
      for (Path f :
          s.filter(p -> p.getFileName().toString().startsWith("star-palace-brightness-"))
              .sorted()
              .toList()) {
        List<JsonNode> records = read(f);
        long neu = QualityRules.countOpening(records, "Nếu ");
        assertThat(neu * 10)
            .as(f.getFileName() + " Nếu openings")
            .isLessThanOrEqualTo(records.size());
      }
    }
  }

  /** Every {@code .jsonl} under {@code root} except the top-level frames and daily files. */
  static List<Path> gatedFiles(Path root) throws IOException {
    Set<Path> excluded = Set.of(root.resolve("frames.vi.jsonl"), root.resolve("daily.vi.jsonl"));
    try (Stream<Path> s = Files.walk(root)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
          .filter(p -> !excluded.contains(p))
          .sorted()
          .toList();
    }
  }
}
