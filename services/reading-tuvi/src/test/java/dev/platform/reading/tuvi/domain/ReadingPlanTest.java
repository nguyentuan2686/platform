package dev.platform.reading.tuvi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.ReadingPlan.Line;
import dev.platform.reading.tuvi.domain.ReadingPlan.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadingPlanTest {

  private final List<Section> plan = ReadingPlan.basic(ChartFixture.load());

  private Section section(String id) {
    return plan.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static List<String> keys(Line line) {
    return line.lookupOrder().stream().map(KnowledgeKey::value).toList();
  }

  @Test
  void sections_come_in_the_fixed_order() {
    assertThat(plan.stream().map(Section::id))
        .containsExactly(
            "greeting",
            "menh",
            "than",
            "cuc",
            "PhuMau",
            "PhucDuc",
            "DienTrach",
            "QuanLoc",
            "NoBoc",
            "ThienDi",
            "TatAch",
            "TaiBach",
            "TuTuc",
            "PhuThe",
            "HuynhDe",
            "closing",
            "disclaimer");
  }

  @Test
  void menh_has_intro_and_major_star_with_fallbacks() {
    List<Line> lines = section("menh").lines();

    assertThat(keys(lines.get(0))).containsExactly("tuvi:palace=Menh");
    assertThat(lines.get(0).vars()).containsEntry("palace", "Mệnh");
    assertThat(keys(lines.get(1)))
        .containsExactly(
            "tuvi:star=ThienTuong:palace=Menh:brightness=Dac",
            "tuvi:star=ThienTuong:palace=Menh",
            "tuvi:star=ThienTuong");
    assertThat(lines.get(1).vars()).containsEntry("star", "Thiên Tướng");
    assertThat(lines).hasSize(2);
  }

  @Test
  void star_without_brightness_skips_the_brightness_key() {
    Palace p =
        new Palace(
            "Menh",
            "Mệnh",
            "Yin",
            false,
            new Palace.Decade(6, 15),
            List.of(new StarPlacement("ThienCo", "Thiên Cơ", null, null)),
            List.of(),
            false,
            false);
    Chart base = ChartFixture.load();
    List<Palace> palaces =
        base.palaces().stream().map(q -> q.key().equals("Menh") ? p : q).toList();
    Chart chart =
        new Chart(
            base.cucNameVi(),
            base.thanPalace(),
            base.tuan(),
            base.triet(),
            palaces,
            base.lunarBirthYear());

    Section menh =
        ReadingPlan.basic(chart).stream().filter(s -> s.id().equals("menh")).findFirst().get();

    assertThat(keys(menh.lines().get(1)))
        .containsExactly("tuvi:star=ThienCo:palace=Menh", "tuvi:star=ThienCo");
  }

  @Test
  void palace_without_major_star_gets_no_major_line() {
    List<Line> lines = section("QuanLoc").lines();

    assertThat(lines).hasSize(2);
    assertThat(keys(lines.get(1))).containsExactly("tuvi:palace=QuanLoc:major=none");
  }

  @Test
  void natal_mutagen_and_marks_become_lines() {
    List<List<String>> phucDuc =
        section("PhucDuc").lines().stream().map(ReadingPlanTest::keys).toList();
    assertThat(phucDuc).contains(List.of("tuvi:palace=PhucDuc:tuhoa=Loc"));

    List<List<String>> thienDi =
        section("ThienDi").lines().stream().map(ReadingPlanTest::keys).toList();
    assertThat(thienDi)
        .contains(
            List.of("tuvi:palace=ThienDi:tuhoa=Khoa"), List.of("tuvi:palace=ThienDi:tuhoa=Quyen"));
    assertThat(thienDi).noneMatch(k -> k.get(0).contains(":mark="));

    List<List<String>> tuTuc =
        section("TuTuc").lines().stream().map(ReadingPlanTest::keys).toList();
    assertThat(tuTuc)
        .contains(List.of("tuvi:palace=TuTuc:mark=Tuan"), List.of("tuvi:palace=TuTuc:mark=Triet"));
  }

  @Test
  void than_section_names_the_than_palace_and_has_no_star_lines() {
    List<Line> lines = section("than").lines();

    assertThat(lines).hasSize(1);
    assertThat(keys(lines.get(0))).containsExactly("tuvi:frame=than_intro");
    assertThat(lines.get(0).vars()).containsEntry("palace", "Tài Bạch");
  }

  @Test
  void cuc_and_frame_sections() {
    assertThat(section("cuc").lines().get(0).vars()).containsEntry("cuc", "Hỏa lục cục");
    assertThat(keys(section("greeting").lines().get(0))).containsExactly("tuvi:frame=greeting");
    assertThat(keys(section("disclaimer").lines().get(0))).containsExactly("tuvi:frame=disclaimer");
  }

  @Test
  void section_titles_use_seeded_frames_and_vars() {
    assertThat(section("menh").titleFrame()).contains("menh_title");
    assertThat(section("menh").vars()).containsEntry("palace", "Mệnh");
    assertThat(section("than").titleFrame()).contains("than_title");
    assertThat(section("than").vars()).containsEntry("palace", "Tài Bạch");
    assertThat(section("cuc").titleFrame()).contains("cuc_title");
    assertThat(section("cuc").vars()).containsEntry("cuc", "Hỏa lục cục");
    assertThat(section("PhuMau").titleFrame()).contains("palace_title");
    assertThat(section("PhuMau").vars()).containsEntry("palace", "Phụ Mẫu");
    assertThat(section("greeting").titleFrame()).isEmpty();
    assertThat(section("closing").titleFrame()).isEmpty();
    assertThat(section("disclaimer").titleFrame()).isEmpty();
  }
}
