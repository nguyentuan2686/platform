package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.KeyCatalog;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the knowledge files with the key catalog (cwd is the module directory). Records outside
 * the catalog always fail; the 100% P1 gate is switched on in Task 4 of Plan F1.2b.
 */
class KnowledgeCoverageTest {

  private static final boolean P1_GATE = false;
  private static final Path KNOWLEDGE = Path.of("..", "..", "knowledge", "tuvi");

  private static Set<String> keysIn(Path file) throws IOException {
    Set<String> out = new HashSet<>();
    for (String line : Files.readAllLines(file)) {
      if (!line.isBlank()) {
        out.add(JsonMapper.shared().readTree(line).get("key").stringValue());
      }
    }
    return out;
  }

  private static List<Path> jsonlFiles() throws IOException {
    try (Stream<Path> s = Files.walk(KNOWLEDGE)) {
      return s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
    }
  }

  @Test
  void every_record_key_is_in_the_catalog_and_reports_coverage() throws Exception {
    Set<String> p1 = new HashSet<>();
    KeyCatalog.p1().forEach(k -> p1.add(k.value()));
    Set<String> allowed = new HashSet<>(p1);
    for (Area a : Area.values()) {
      for (Level l : Level.values()) {
        allowed.add(KnowledgeKey.daily(a, l).value());
      }
    }
    allowed.addAll(keysIn(KNOWLEDGE.resolve("frames.vi.jsonl")));

    Set<String> present = new HashSet<>();
    for (Path file : jsonlFiles()) {
      present.addAll(keysIn(file));
    }

    List<String> report = new ArrayList<>();
    for (String palace : KeyCatalog.P2_PALACES) {
      Set<String> p2 = new HashSet<>();
      KeyCatalog.p2(palace).forEach(k -> p2.add(k.value()));
      allowed.addAll(p2);
      report.add("P2 " + palace + " covered " + count(p2, present) + "/" + p2.size());
    }
    int covered = count(p1, present);
    System.out.println("P1 covered " + covered + "/" + p1.size());
    report.forEach(System.out::println);

    Set<String> outside = new HashSet<>(present);
    outside.removeAll(allowed);
    assertThat(outside).as("record keys outside the catalog").isEmpty();
    if (P1_GATE) {
      assertThat(covered).as("P1 coverage").isEqualTo(p1.size());
    }
  }

  private static int count(Set<String> wanted, Set<String> present) {
    return (int) wanted.stream().filter(present::contains).count();
  }
}
