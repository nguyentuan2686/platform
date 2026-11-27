package dev.platform.reading.tuvi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.FlowLayer.Mutagen;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodayScorerTest {

  private final Chart chart = ChartFixture.load();

  private static FlowLayer layer(String palaceKey, Mutagen... m) {
    return ChartFixture.layer(palaceKey, m);
  }

  @Test
  void daily_loc_in_tai_bach_is_thuan() {
    Chart withStar = chartWithStarInTaiBach();
    var day =
        new DailyFacts(layer("Menh"), layer("Menh"), layer("Menh", new Mutagen("ThaiAm", "Loc")));

    assertThat(TodayScorer.score(withStar, day)).containsEntry(Area.TienBac, Level.Thuan);
  }

  @Test
  void daily_ky_in_tat_ach_is_luu_y() {
    var day =
        new DailyFacts(layer("Menh"), layer("Menh"), layer("Menh", new Mutagen("ThaiDuong", "Ky")));

    assertThat(TodayScorer.score(chart, day)).containsEntry(Area.SucKhoe, Level.LuuY);
  }

  @Test
  void daily_khoa_in_thien_di_is_binh() {
    var day =
        new DailyFacts(layer("Menh"), layer("Menh"), layer("Menh", new Mutagen("VuKhuc", "Khoa")));

    assertThat(TodayScorer.score(chart, day)).containsEntry(Area.DiLai, Level.Binh);
  }

  @Test
  void nothing_touching_tat_ach_is_binh() {
    var day = new DailyFacts(layer("Menh"), layer("Menh"), layer("Menh"));

    assertThat(TodayScorer.score(chart, day)).containsEntry(Area.SucKhoe, Level.Binh);
  }

  @Test
  void daily_layer_on_palace_plus_monthly_loc_is_thuan() {
    Chart withStar = chartWithStarInTaiBach();
    var day =
        new DailyFacts(
            layer("Menh"), layer("Menh", new Mutagen("ThaiAm", "Loc")), layer("TaiBach"));

    assertThat(TodayScorer.score(withStar, day)).containsEntry(Area.TienBac, Level.Thuan);
  }

  @Test
  void result_is_ordered_as_the_enum_and_covers_all_areas() {
    var day = new DailyFacts(layer("Menh"), layer("Menh"), layer("Menh"));

    Map<Area, Level> scores = TodayScorer.score(chart, day);

    assertThat(scores.keySet()).containsExactly(Area.values());
  }

  @Test
  void triet_and_ky_both_subtract() {
    // TatAch (Wu): daily Ky (-2) in a Triet branch (-1) stays LuuY; monthly Quyen and unknown
    // add nothing.
    Chart triet =
        new Chart(
            chart.cucNameVi(),
            chart.thanPalace(),
            chart.tuan(),
            List.of("Wu", "Hai"),
            chart.palaces(),
            chart.lunarBirthYear());
    var day =
        new DailyFacts(
            layer("Menh"),
            layer("Menh", new Mutagen("ThaiDuong", "Quyen"), new Mutagen("ThaiDuong", "Weird")),
            layer("Menh", new Mutagen("ThaiDuong", "Ky")));

    assertThat(TodayScorer.score(triet, day)).containsEntry(Area.SucKhoe, Level.LuuY);
  }

  /** Same chart, but TaiBach holds the minor star ThaiAm so that mutagens can land there. */
  private Chart chartWithStarInTaiBach() {
    List<Palace> palaces =
        chart.palaces().stream()
            .map(
                p ->
                    p.key().equals("TaiBach")
                        ? new Palace(
                            p.key(),
                            p.nameVi(),
                            p.branch(),
                            p.isBody(),
                            p.decade(),
                            List.of(),
                            List.of(new StarPlacement("ThaiAm", "Thái Âm", null, null)),
                            p.tuan(),
                            p.triet())
                        : p)
            .toList();
    return new Chart(
        chart.cucNameVi(),
        chart.thanPalace(),
        chart.tuan(),
        chart.triet(),
        palaces,
        chart.lunarBirthYear());
  }
}
