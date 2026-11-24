package dev.platform.reading.tuvi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class KeyCatalogTest {

  private static Set<String> values(List<KnowledgeKey> keys) {
    Set<String> out = new HashSet<>();
    keys.forEach(k -> out.add(k.value()));
    return out;
  }

  @Test
  void p1_has_278_unique_keys() {
    List<KnowledgeKey> p1 = KeyCatalog.p1();
    assertThat(p1).hasSize(278);
    assertThat(values(p1)).hasSize(278);
  }

  @Test
  void p1_contains_the_expected_samples() {
    assertThat(values(KeyCatalog.p1()))
        .contains(
            "tuvi:star=TuVi",
            "tuvi:palace=Menh",
            "tuvi:palace=QuanLoc:major=none",
            "tuvi:palace=PhuThe:mark=Triet",
            "tuvi:palace=TaiBach:tuhoa=Ky",
            "tuvi:star=ThatSat:palace=Menh");
  }

  @Test
  void p2_has_70_keys_per_palace() {
    List<KnowledgeKey> menh = KeyCatalog.p2("Menh");
    assertThat(menh).hasSize(70);
    assertThat(values(menh)).hasSize(70).contains("tuvi:star=TuVi:palace=Menh:brightness=Mieu");
    assertThat(KeyCatalog.P2_PALACES).containsExactly("Menh", "QuanLoc", "TaiBach", "PhuThe");
  }

  @Test
  void every_basic_plan_key_is_in_the_catalog() {
    Set<String> allowed = values(KeyCatalog.p1());
    for (String palace : KeyCatalog.PALACES) {
      allowed.addAll(values(KeyCatalog.p2(palace)));
    }
    List<String> missing = new ArrayList<>();
    for (ReadingPlan.Section s : ReadingPlan.basic(ChartFixture.load())) {
      for (ReadingPlan.Line line : s.lines()) {
        for (KnowledgeKey k : line.lookupOrder()) {
          if (!k.value().startsWith("tuvi:frame=") && !allowed.contains(k.value())) {
            missing.add(k.value());
          }
        }
      }
    }
    assertThat(missing).isEmpty();
  }

  @Test
  void lists_match_the_chart_svc_contract() throws Exception {
    JsonNode schemas =
        YAMLMapper.builder()
            .build()
            .readTree(
                Files.readString(Path.of("..", "..", "contracts", "openapi", "chart-svc.yaml")))
            .get("components")
            .get("schemas");
    assertThat(enumOf(schemas, "PalaceKey"))
        .containsExactlyInAnyOrderElementsOf(KeyCatalog.PALACES);
    assertThat(enumOf(schemas, "Brightness"))
        .containsExactlyInAnyOrderElementsOf(KeyCatalog.BRIGHTNESS);
    assertThat(enumOf(schemas, "Mutagen")).containsExactlyInAnyOrderElementsOf(KeyCatalog.MUTAGENS);
    assertThat(enumOf(schemas, "StarKey").subList(0, 14))
        .containsExactlyInAnyOrderElementsOf(KeyCatalog.MAJOR_STARS);
  }

  private static List<String> enumOf(JsonNode schemas, String name) {
    List<String> out = new ArrayList<>();
    schemas.get(name).get("enum").forEach(n -> out.add(n.asString()));
    return out;
  }
}
