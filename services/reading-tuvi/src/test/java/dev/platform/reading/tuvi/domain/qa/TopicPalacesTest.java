package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TopicPalacesTest {

  private static List<String> of(QaTopic topic, String q) {
    return TopicPalaces.palaces(topic, q, Optional.empty());
  }

  @Test
  void each_topic_has_its_base_list_after_menh() {
    assertThat(of(QaTopic.TinhDuyen, "x")).containsExactly("Menh", "PhuThe");
    assertThat(of(QaTopic.SuNghiep, "x")).containsExactly("Menh", "QuanLoc", "NoBoc");
    assertThat(of(QaTopic.TaiLoc, "x")).containsExactly("Menh", "TaiBach", "DienTrach");
    assertThat(of(QaTopic.SucKhoe, "x")).containsExactly("Menh", "TatAch");
    assertThat(of(QaTopic.GiaDao, "x")).containsExactly("Menh", "PhuMau", "HuynhDe", "TuTuc");
  }

  @Test
  void keyword_extras_are_appended_and_distinct() {
    assertThat(of(QaTopic.TaiLoc, "con cái và nhà đất"))
        .containsExactly("Menh", "TaiBach", "DienTrach", "TuTuc");
    assertThat(of(QaTopic.TinhDuyen, "chồng tôi có đi xa không"))
        .containsExactly("Menh", "PhuThe", "ThienDi");
  }

  @Test
  void each_keyword_group_maps_to_its_palace() {
    assertThat(of(QaTopic.SucKhoe, "bố mẹ")).contains("PhuMau");
    assertThat(of(QaTopic.SucKhoe, "anh em")).contains("HuynhDe");
    assertThat(of(QaTopic.SucKhoe, "đồng nghiệp")).contains("NoBoc");
    assertThat(of(QaTopic.SucKhoe, "du lịch")).contains("ThienDi");
    assertThat(of(QaTopic.SucKhoe, "người yêu")).contains("PhuThe");
    assertThat(of(QaTopic.SucKhoe, "mua nhà")).contains("DienTrach");
    assertThat(of(QaTopic.SucKhoe, "con trai")).contains("TuTuc");
  }

  @Test
  void result_is_capped_at_five() {
    List<String> out =
        of(QaTopic.GiaDao, "con cái, nhà cửa, đi xa, bạn bè, vợ chồng, anh chị, cha mẹ");
    assertThat(out).hasSize(5).startsWith("Menh", "PhuMau", "HuynhDe", "TuTuc");
  }

  @Test
  void van_nam_uses_the_yearly_palace_or_thien_di() {
    assertThat(TopicPalaces.palaces(QaTopic.VanNam, "x", Optional.of("QuanLoc")))
        .containsExactly("Menh", "QuanLoc");
    assertThat(TopicPalaces.palaces(QaTopic.VanNam, "x", Optional.empty()))
        .containsExactly("Menh", "ThienDi");
  }

  @Test
  void menh_yearly_palace_is_not_duplicated() {
    assertThat(TopicPalaces.palaces(QaTopic.VanNam, "x", Optional.of("Menh")))
        .containsExactly("Menh");
  }

  @Test
  void keywords_need_word_boundaries() {
    assertThat(of(QaTopic.SucKhoe, "vợi")).containsExactly("Menh", "TatAch");
  }
}
