package dev.platform.reading.tuvi.adapter.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates every repository knowledge file against the contract (cwd is the module dir). */
class KnowledgeFilesTest {

  static final Path ROOT = Path.of("..", "..");
  static final Path KNOWLEDGE = ROOT.resolve("knowledge").resolve("tuvi");
  static final List<String> FRAME_IDS =
      List.of(
          "greeting",
          "menh_title",
          "than_title",
          "than_intro",
          "cuc_title",
          "cuc_intro",
          "palace_title",
          "missing",
          "closing",
          "disclaimer",
          "today_opening",
          "today_closing",
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

  private static Schema schema() throws IOException {
    Path file = ROOT.resolve("contracts").resolve("knowledge").resolve("tuvi-record.schema.json");
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(Files.readString(file));
  }

  private static List<Path> files() throws IOException {
    try (Stream<Path> s = Files.walk(KNOWLEDGE)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
          .sorted()
          .toList();
    }
  }

  @Test
  void every_line_passes_schema_and_key_grammar_without_duplicates() throws Exception {
    Schema schema = schema();
    Set<String> identities = new HashSet<>();
    int records = 0;
    for (Path file : files()) {
      int n = 0;
      for (String line : Files.readAllLines(file)) {
        n++;
        if (line.isBlank()) {
          continue;
        }
        JsonNode node = JsonMapper.shared().readTree(line);
        assertThat(schema.validate(node)).as("%s:%d", KNOWLEDGE.relativize(file), n).isEmpty();
        new KnowledgeKey(node.get("key").stringValue());
        String identity =
            node.get("key").stringValue()
                + "|"
                + node.get("school").stringValue()
                + "|"
                + node.get("lang").stringValue();
        assertThat(identities.add(identity)).as("duplicate at %s:%d", file, n).isTrue();
        records++;
      }
    }
    assertThat(records).isGreaterThanOrEqualTo(24);
  }

  @Test
  void every_frame_id_is_present_exactly_once() throws Exception {
    List<String> keys = new ArrayList<>();
    for (String line : Files.readAllLines(KNOWLEDGE.resolve("frames.vi.jsonl"))) {
      keys.add(JsonMapper.shared().readTree(line).get("key").stringValue());
    }
    assertThat(keys)
        .containsExactlyInAnyOrderElementsOf(
            FRAME_IDS.stream().map(i -> "tuvi:frame=" + i).toList());
    assertThat(keys).hasSize(25);
    for (String line : Files.readAllLines(KNOWLEDGE.resolve("frames.vi.jsonl"))) {
      assertThat(JsonMapper.shared().readTree(line).get("status").stringValue())
          .as(line)
          .isEqualTo("approved");
    }
  }

  @Test
  void daily_covers_four_areas_by_three_levels_all_approved() throws Exception {
    Set<String> keys = new HashSet<>();
    for (String line : Files.readAllLines(KNOWLEDGE.resolve("daily.vi.jsonl"))) {
      JsonNode n = JsonMapper.shared().readTree(line);
      assertThat(n.get("status").stringValue()).isEqualTo("approved");
      keys.add(n.get("key").stringValue());
    }
    assertThat(keys).hasSize(12);
  }
}
