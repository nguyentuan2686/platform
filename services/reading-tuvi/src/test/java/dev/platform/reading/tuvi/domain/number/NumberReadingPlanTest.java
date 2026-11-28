package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.KeyCatalog;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NumberReadingPlanTest {

  private static final Profile THO_KHAM = Profile.of(1990, false, Gender.MALE, 2026);

  private static List<String> plan(NumberKind kind, Vehicle v, String raw) {
    NumberInput in = NumberInput.parse(kind, v, raw);
    return NumberReadingPlan.keys(in, THO_KHAM, NumberScorer.score(in, THO_KHAM));
  }

  @Test
  void golden_sim_plan_in_spec_order() {
    assertThat(plan(NumberKind.SIM, null, "0912345678"))
        .containsExactly(
            "so:intro=sim",
            "so:menh=tho",
            "so:element=tuong_hop",
            "so:que=4",
            "so:amduong=can_bang",
            "so:cungphi=hop_mot",
            "so:nut=thap",
            "so:pair=78",
            "so:band=kha_hop",
            "so:closing");
  }

  @Test
  void at_most_three_pairs_good_first() {
    // good 39, 68, 86 and avoid 49: the three good ones win
    assertThat(plan(NumberKind.BANK, null, "39496886"))
        .filteredOn(k -> k.startsWith("so:pair="))
        .containsExactly("so:pair=39", "so:pair=68", "so:pair=86");
    // good 39, 68 then avoid 49
    assertThat(plan(NumberKind.BANK, null, "394968"))
        .filteredOn(k -> k.startsWith("so:pair="))
        .containsExactly("so:pair=39", "so:pair=68", "so:pair=49");
    assertThat(plan(NumberKind.BANK, null, "3968867999"))
        .filteredOn(k -> k.startsWith("so:pair="))
        .hasSize(3);
  }

  @Test
  void intro_follows_the_kind_and_nut_maps_to_cao_vua_thap() {
    assertThat(plan(NumberKind.BANK, null, "123456")).first().isEqualTo("so:intro=bank");
    assertThat(plan(NumberKind.PLATE, Vehicle.CAR, "30A12345")).first().isEqualTo("so:intro=plate");
    // 1+1+2+5 = 9 nút
    assertThat(plan(NumberKind.PLATE, Vehicle.CAR, "30A1125")).contains("so:nut=cao");
    // 1+1+0+5 = 7 nút
    assertThat(plan(NumberKind.PLATE, Vehicle.CAR, "30A1105")).doesNotContain("so:nut=cao");
    assertThat(plan(NumberKind.PLATE, Vehicle.CAR, "30A1105")).contains("so:nut=vua");
    // 1+1+0+8 = 10 -> 0 nút
    assertThat(plan(NumberKind.PLATE, Vehicle.CAR, "30A1108")).contains("so:nut=thap");
  }

  @Test
  void every_planned_key_is_a_catalog_key() {
    Set<String> catalog = new HashSet<>();
    KeyCatalog.number().forEach(k -> catalog.add(k.value()));
    for (String raw : List.of("0912345678", "0999999999", "0868686868", "0394953000")) {
      for (Gender g : Gender.values()) {
        for (int year : new int[] {1930, 1984, 1990, 2000, 2026}) {
          Profile p = Profile.of(year, false, g, 2026);
          NumberInput in = NumberInput.parse(NumberKind.SIM, null, raw);
          for (String k : NumberReadingPlan.keys(in, p, NumberScorer.score(in, p))) {
            assertThat(catalog).as(k).contains(k);
            new KnowledgeKey(k);
          }
        }
      }
    }
  }
}
