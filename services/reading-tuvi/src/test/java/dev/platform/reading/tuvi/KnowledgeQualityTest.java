package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  /**
   * The Q&A frames (Plan F1.4) are shown verbatim, so they pass the content rules too. The frames
   * file is outside the doctrine gate; its {@code original:frame} source ref is the only exemption.
   */
  @Test
  void qa_frames_pass_the_content_rules() throws IOException {
    List<JsonNode> qa =
        read(KNOWLEDGE.resolve("frames.vi.jsonl")).stream()
            .filter(r -> r.get("key").stringValue().startsWith("tuvi:frame=qa_"))
            .toList();
    assertThat(qa)
        .extracting(r -> r.get("key").stringValue().substring("tuvi:frame=".length()))
        .containsExactlyInAnyOrder(
            "qa_disclaimer",
            "qa_refuse_medical",
            "qa_refuse_legal",
            "qa_refuse_finance",
            "qa_refuse_gambling",
            "qa_refuse_death",
            "qa_refuse_selfharm",
            "qa_refuse_ritual",
            "qa_refuse_offtopic",
            "qa_insufficient",
            "qa_queued",
            "qa_busy",
            "qa_refunded");
    for (JsonNode r : qa) {
      assertThat(r.get("source_ref").stringValue()).isEqualTo("original:frame");
      assertThat(r.get("status").stringValue()).isEqualTo("approved");
    }
    assertThat(QualityRules.checkAll(qa)).allMatch(v -> v.startsWith("rule=source_ref "));
    assertThat(
            qa.stream()
                .filter(r -> r.get("key").stringValue().equals("tuvi:frame=qa_disclaimer"))
                .map(r -> r.get("content").stringValue()))
        .containsExactly(
            "Lời ta nói là nội dung do AI biên soạn từ kho tư liệu cổ, mang tính tham khảo và"
                + " giải trí thôi con nhé. Việc sức khỏe, pháp lý hay tiền bạc lớn, con hãy hỏi"
                + " người có chuyên môn.");
  }

  /**
   * Number-check readings (spec §6): every {@code so:} catalog key has exactly one approved record
   * in {@code number.vi.jsonl}, cited as {@code original:number}, passing the content rules, with
   * no bettable digit pair like {@code 12-34} and no advice to change a bank or plate number.
   */
  @Test
  void number_readings_cover_the_catalog_and_pass_the_content_rules() throws IOException {
    List<JsonNode> records = read(KNOWLEDGE.resolve("number.vi.jsonl"));
    Set<String> catalog = new java.util.HashSet<>();
    dev.platform.reading.tuvi.domain.KeyCatalog.number().forEach(k -> catalog.add(k.value()));
    assertThat(records)
        .extracting(r -> r.get("key").stringValue())
        .containsExactlyInAnyOrderElementsOf(catalog);
    Pattern bettable = Pattern.compile("\\d{2}\\s*[-–]\\s*\\d{2}");
    Pattern changeNumber = Pattern.compile("(?i)(đổi|thay) (số|biển)");
    for (JsonNode r : records) {
      String key = r.get("key").stringValue();
      String content = r.get("content").stringValue();
      assertThat(r.get("status").stringValue()).as(key).isEqualTo("approved");
      assertThat(r.get("source_ref").stringValue()).as(key).isEqualTo("original:number");
      assertThat(bettable.matcher(content).find()).as("digit pair in %s", key).isFalse();
      assertThat(changeNumber.matcher(content).find()).as("change advice in %s", key).isFalse();
    }
    assertThat(QualityRules.checkAll(records)).isEmpty();
  }

  @Test
  void a_number_record_must_cite_original_number() {
    JsonNode bad =
        JsonMapper.shared()
            .readTree(
                "{\"key\":\"so:closing\",\"school\":\"iztro-default\",\"lang\":\"vi\","
                    + "\"content\":\"Ta mong con bình an.\","
                    + "\"source_ref\":\"ziwei-quanshu:juan2:一 命宫\",\"status\":\"approved\"}");
    assertThat(QualityRules.check(bad)).containsExactly("rule=source_ref key=so:closing");
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

  /**
   * The basic reading is shown on public share pages, which must not reveal a date. Only the
   * "today" frames may carry date placeholders; every other knowledge text (basic reading frames,
   * doctrine, daily lines) has no date, lunar or time placeholder and no date-like literal.
   */
  @Test
  void basic_reading_texts_carry_no_date_placeholder_or_date_like_text() throws IOException {
    if (!Files.isDirectory(KNOWLEDGE)) {
      return;
    }
    List<JsonNode> records = new ArrayList<>(read(KNOWLEDGE.resolve("frames.vi.jsonl")));
    for (Path f : gatedFiles(KNOWLEDGE)) {
      records.addAll(read(f));
    }
    Pattern placeholder = Pattern.compile("\\{([A-Za-z_]+)\\}");
    Pattern forbiddenName =
        Pattern.compile("(?i)date|lunar|canchi|birth|day|month|year|time|hour|zone");
    Pattern dateLike =
        Pattern.compile(
            "\\b\\d{1,2}[/.-]\\d{1,2}([/.-]\\d{2,4})?\\b|\\b(19|20)\\d{2}\\b|\\b\\d{1,2}:\\d{2}\\b");
    int checked = 0;
    for (JsonNode r : records) {
      String key = r.get("key").stringValue();
      if (key.startsWith("tuvi:frame=today_") || key.startsWith("tuvi:frame=qa_")) {
        continue;
      }
      checked++;
      String content = r.get("content").stringValue();
      Matcher m = placeholder.matcher(content);
      while (m.find()) {
        assertThat(forbiddenName.matcher(m.group(1)).find())
            .as("date placeholder {%s} in %s", m.group(1), key)
            .isFalse();
      }
      assertThat(dateLike.matcher(content).find()).as("date-like text in %s", key).isFalse();
    }
    assertThat(checked).isGreaterThan(10);
  }
}
