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
  void menh_has_intro_major_star_with_fallbacks_and_minor_mutagen() {
    List<Line> lines = section("menh").lines();

    assertThat(keys(lines.get(0))).containsExactly("tuvi:palace=Menh");
    assertThat(lines.get(0).vars()).containsEntry("palace", "Mệnh");
    assertThat(keys(lines.get(1)))
        .containsExactly(
            "tuvi:star=TuVi:palace=Menh:brightness=Mieu",
            "tuvi:star=TuVi:palace=Menh",
            "tuvi:star=TuVi");
    assertThat(lines.get(1).vars()).containsEntry("star", "Tử Vi");
    assertThat(keys(lines.get(2))).containsExactly("tuvi:palace=Menh:tuhoa=Khoa");
    assertThat(lines).hasSize(3);
  }

  @Test
  void star_without_brightness_skips_the_brightness_key() {
    assertThat(keys(section("PhuThe").lines().get(1)))
        .containsExactly("tuvi:star=ThienCo:palace=PhuThe", "tuvi:star=ThienCo");
  }

  @Test
  void palace_without_major_star_gets_no_major_line() {
    List<Line> lines = section("TaiBach").lines();

    assertThat(lines).hasSize(2);
    assertThat(keys(lines.get(1))).containsExactly("tuvi:palace=TaiBach:major=none");
  }

  @Test
  void natal_mutagen_and_marks_become_lines() {
    List<List<String>> phuThe =
        section("PhuThe").lines().stream().map(ReadingPlanTest::keys).toList();
    assertThat(phuThe)
        .contains(List.of("tuvi:palace=PhuThe:tuhoa=Ky"), List.of("tuvi:palace=PhuThe:mark=Triet"));

    List<List<String>> thienDi =
        section("ThienDi").lines().stream().map(ReadingPlanTest::keys).toList();
    assertThat(thienDi).contains(List.of("tuvi:palace=ThienDi:mark=Tuan"));
    assertThat(thienDi).doesNotContain(List.of("tuvi:palace=ThienDi:mark=Triet"));
  }

  @Test
  void than_section_names_the_than_palace_and_has_no_star_lines() {
    List<Line> lines = section("than").lines();

    assertThat(lines).hasSize(1);
    assertThat(keys(lines.get(0))).containsExactly("tuvi:frame=than_intro");
    assertThat(lines.get(0).vars()).containsEntry("palace", "Quan Lộc");
  }

  @Test
  void cuc_and_frame_sections() {
    assertThat(section("cuc").lines().get(0).vars()).containsEntry("cuc", "Kim tứ cục");
    assertThat(keys(section("greeting").lines().get(0))).containsExactly("tuvi:frame=greeting");
    assertThat(keys(section("disclaimer").lines().get(0))).containsExactly("tuvi:frame=disclaimer");
  }

  @Test
  void section_titles_use_seeded_frames_and_vars() {
    assertThat(section("menh").titleFrame()).contains("menh_title");
    assertThat(section("menh").vars()).containsEntry("palace", "Mệnh");
    assertThat(section("than").titleFrame()).contains("than_title");
    assertThat(section("than").vars()).containsEntry("palace", "Quan Lộc");
    assertThat(section("cuc").titleFrame()).contains("cuc_title");
    assertThat(section("cuc").vars()).containsEntry("cuc", "Kim tứ cục");
    assertThat(section("PhuMau").titleFrame()).contains("palace_title");
    assertThat(section("PhuMau").vars()).containsEntry("palace", "Phụ Mẫu");
    assertThat(section("greeting").titleFrame()).isEmpty();
    assertThat(section("closing").titleFrame()).isEmpty();
    assertThat(section("disclaimer").titleFrame()).isEmpty();
  }
}
