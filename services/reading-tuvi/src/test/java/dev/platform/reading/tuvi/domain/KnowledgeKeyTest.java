package dev.platform.reading.tuvi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeKeyTest {

  @Test
  void builds_star_keys_in_facet_order() {
    assertThat(KnowledgeKey.star("TuVi", "Menh", "Mieu").value())
        .isEqualTo("tuvi:star=TuVi:palace=Menh:brightness=Mieu");
    assertThat(KnowledgeKey.star("TuVi", "Menh").value()).isEqualTo("tuvi:star=TuVi:palace=Menh");
    assertThat(KnowledgeKey.star("TuVi").value()).isEqualTo("tuvi:star=TuVi");
  }

  @Test
  void builds_palace_keys() {
    assertThat(KnowledgeKey.palaceTuhoa("PhuThe", "Ky").value())
        .isEqualTo("tuvi:palace=PhuThe:tuhoa=Ky");
    assertThat(KnowledgeKey.palaceNoMajor("TaiBach").value())
        .isEqualTo("tuvi:palace=TaiBach:major=none");
    assertThat(KnowledgeKey.palaceMark("ThienDi", "Tuan").value())
        .isEqualTo("tuvi:palace=ThienDi:mark=Tuan");
    assertThat(KnowledgeKey.palace("Menh").value()).isEqualTo("tuvi:palace=Menh");
  }

  @Test
  void builds_daily_and_frame_keys() {
    assertThat(KnowledgeKey.daily(Area.CongViec, Level.Thuan).value())
        .isEqualTo("tuvi:daily:area=CongViec:level=Thuan");
    assertThat(KnowledgeKey.frame("greeting").value()).isEqualTo("tuvi:frame=greeting");
  }

  @Test
  void rejects_keys_outside_the_grammar() {
    assertThatThrownBy(() -> new KnowledgeKey("tuvi:Star=TuVi"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KnowledgeKey("astro:planet=Sun"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KnowledgeKey("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KnowledgeKey(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
