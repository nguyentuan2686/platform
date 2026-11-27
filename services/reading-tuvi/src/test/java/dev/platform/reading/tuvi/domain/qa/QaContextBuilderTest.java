package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.ChartFixture;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.FlowLayer.Mutagen;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.Palace;
import dev.platform.reading.tuvi.domain.ReadingPlan;
import dev.platform.reading.tuvi.domain.StarPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QaContextBuilderTest {

  private final Chart chart = ChartFixture.load();

  private List<List<KnowledgeKey>> chains(String... palaces) {
    List<List<KnowledgeKey>> out = new ArrayList<>();
    for (String key : palaces) {
      ReadingPlan.palaceLines(chart, chart.palace(key))
          .forEach(line -> out.add(line.lookupOrder()));
    }
    return out;
  }

  @Test
  void tinh_duyen_chains_are_menh_then_phu_the_lines() {
    QaContext ctx =
        QaContextBuilder.build(chart, Optional.empty(), QaTopic.TinhDuyen, "tình duyên ra sao");
    assertThat(ctx.lookupChains()).isEqualTo(chains("Menh", "PhuThe"));
    assertThat(ctx.facts())
        .extracting(PalaceFact::palaceNameVi)
        .containsExactly(chart.palace("Menh").nameVi(), chart.palace("PhuThe").nameVi());
    assertThat(ctx.yearlyFact()).isEmpty();
  }

  @Test
  void facts_render_stars_with_vietnamese_brightness_and_tu_hoa() {
    QaContext ctx = QaContextBuilder.build(chart, Optional.empty(), QaTopic.GiaDao, "x");
    List<String> all = ctx.facts().stream().flatMap(f -> f.stars().stream()).toList();
    assertThat(all).isNotEmpty();
    assertThat(all)
        .allMatch(s -> s.matches(".+ \\((Miếu|Vượng|Đắc|Bình|Hãm)(, Hóa (Lộc|Quyền|Khoa|Kỵ))?\\)"));
  }

  @Test
  void star_rendering_handles_missing_brightness_and_mutagen() {
    assertThat(QaContextBuilder.renderStar(new StarPlacement("k", "Sao", null, "Ky")))
        .isEqualTo("Sao (Hóa Kỵ)");
    assertThat(QaContextBuilder.renderStar(new StarPlacement("k", "Sao", null, null)))
        .isEqualTo("Sao");
    assertThat(QaContextBuilder.renderStar(new StarPlacement("k", "Sao", "Mieu", "Quyen")))
        .isEqualTo("Sao (Miếu, Hóa Quyền)");
  }

  @Test
  void van_nam_carries_the_yearly_fact() {
    StarPlacement star = chart.palace("Menh").major().get(0);
    FlowLayer yearly = ChartFixture.layer("QuanLoc", new Mutagen(star.key(), "Loc"));
    QaContext ctx =
        QaContextBuilder.build(chart, Optional.of(yearly), QaTopic.VanNam, "năm nay thế nào");
    assertThat(ctx.facts())
        .extracting(PalaceFact::palaceNameVi)
        .containsExactly(chart.palace("Menh").nameVi(), chart.palace("QuanLoc").nameVi());
    assertThat(ctx.yearlyFact())
        .contains(
            "Năm nay lưu niên ở cung "
                + chart.palace("QuanLoc").nameVi()
                + "; Tứ Hóa năm: "
                + star.nameVi()
                + " Hóa Lộc");
  }

  @Test
  void yearly_fact_without_mutagens_has_no_tu_hoa_part() {
    QaContext ctx =
        QaContextBuilder.build(
            chart, Optional.of(ChartFixture.layer("QuanLoc")), QaTopic.VanNam, "x");
    assertThat(ctx.yearlyFact())
        .contains("Năm nay lưu niên ở cung " + chart.palace("QuanLoc").nameVi());
  }

  @Test
  void other_topics_ignore_the_yearly_layer() {
    QaContext ctx =
        QaContextBuilder.build(
            chart, Optional.of(ChartFixture.layer("QuanLoc")), QaTopic.TaiLoc, "x");
    assertThat(ctx.yearlyFact()).isEmpty();
  }

  @Test
  void a_named_year_labels_the_yearly_fact_for_any_topic() {
    FlowLayer y = ChartFixture.layer("QuanLoc");
    TimingIntent in2027 = new TimingIntent(false, Optional.of(2027));
    QaContext ctx =
        QaContextBuilder.build(chart, Optional.of(y), QaTopic.SuNghiep, "x", in2027, 2026);
    assertThat(ctx.yearlyFact()).contains("Năm 2027 lưu niên ở cung Quan Lộc");
    assertThat(ctx.timing()).isEmpty();

    TimingIntent thisYear = new TimingIntent(false, Optional.of(2026));
    assertThat(
            QaContextBuilder.build(chart, Optional.of(y), QaTopic.TaiLoc, "x", thisYear, 2026)
                .yearlyFact())
        .contains("Năm nay lưu niên ở cung Quan Lộc");
  }

  @Test
  void van_nam_with_a_named_year_grounds_on_that_years_palace() {
    QaContext ctx =
        QaContextBuilder.build(
            chart,
            Optional.of(ChartFixture.layer("TaiBach")),
            QaTopic.VanNam,
            "x",
            new TimingIntent(false, Optional.of(2028)),
            2026);
    assertThat(ctx.facts())
        .extracting(PalaceFact::palaceNameVi)
        .containsExactly("Mệnh", "Tài Bạch");
  }

  @Test
  void timing_intent_adds_the_thoi_van_lines_of_the_topic_palaces() {
    QaContext ctx =
        QaContextBuilder.build(
            chart,
            Optional.empty(),
            QaTopic.TinhDuyen,
            "khi nào",
            new TimingIntent(true, Optional.empty()),
            2026);
    assertThat(ctx.timing())
        .isEqualTo(TimingLines.lines(chart, List.of("Menh", "PhuThe"), 2026))
        .hasSize(3);
    assertThat(ctx.yearlyFact()).isEmpty();
    assertThat(QaContextBuilder.build(chart, Optional.empty(), QaTopic.TinhDuyen, "x").timing())
        .isEmpty();
  }

  @Test
  void tuan_and_triet_flags_follow_the_chart() {
    QaContext ctx = QaContextBuilder.build(chart, Optional.empty(), QaTopic.GiaDao, "x");
    for (PalaceFact f : ctx.facts()) {
      Palace p =
          chart.palaces().stream()
              .filter(x -> x.nameVi().equals(f.palaceNameVi()))
              .findFirst()
              .orElseThrow();
      assertThat(f.tuan()).isEqualTo(chart.inTuan(p));
      assertThat(f.triet()).isEqualTo(chart.inTriet(p));
    }
  }
}
