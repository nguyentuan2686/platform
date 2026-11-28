package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NapAmTest {

  @Test
  void known_years() {
    assertThat(NapAm.canChi(1984)).isEqualTo("Giáp Tý");
    assertThat(NapAm.name(1984)).isEqualTo("Hải Trung Kim");
    assertThat(NapAm.element(1984)).isEqualTo(Element.KIM);
    assertThat(NapAm.canChi(1990)).isEqualTo("Canh Ngọ");
    assertThat(NapAm.name(1990)).isEqualTo("Lộ Bàng Thổ");
    assertThat(NapAm.element(1990)).isEqualTo(Element.THO);
    assertThat(NapAm.canChi(2027)).isEqualTo("Đinh Mùi");
    assertThat(NapAm.name(2027)).isEqualTo("Thiên Hà Thủy");
    assertThat(NapAm.element(2027)).isEqualTo(Element.THUY);
    assertThat(NapAm.canChi(1999)).isEqualTo("Kỷ Mão");
    assertThat(NapAm.name(1999)).isEqualTo("Thành Đầu Thổ");
    assertThat(NapAm.name(1930)).isEqualTo("Lộ Bàng Thổ");
  }

  @Test
  void sixty_years_give_thirty_names_each_used_twice_and_sixty_can_chi() {
    Map<String, Integer> uses = new HashMap<>();
    Set<String> canChi = new HashSet<>();
    for (int y = 1984; y < 2044; y++) {
      uses.merge(NapAm.name(y), 1, Integer::sum);
      canChi.add(NapAm.canChi(y));
      assertThat(NapAm.name(y + 60)).isEqualTo(NapAm.name(y));
      assertThat(NapAm.name(y)).endsWith(NapAm.element(y).label());
    }
    assertThat(uses).hasSize(30);
    assertThat(uses.values()).allMatch(n -> n == 2);
    assertThat(canChi).hasSize(60);
  }

  @Test
  void element_cycles() {
    assertThat(Element.KIM.generates()).isEqualTo(Element.THUY);
    assertThat(Element.THUY.generates()).isEqualTo(Element.MOC);
    assertThat(Element.MOC.generates()).isEqualTo(Element.HOA);
    assertThat(Element.HOA.generates()).isEqualTo(Element.THO);
    assertThat(Element.THO.generates()).isEqualTo(Element.KIM);
    assertThat(Element.KIM.controls()).isEqualTo(Element.MOC);
    assertThat(Element.MOC.controls()).isEqualTo(Element.THO);
    assertThat(Element.THO.controls()).isEqualTo(Element.THUY);
    assertThat(Element.THUY.controls()).isEqualTo(Element.HOA);
    assertThat(Element.HOA.controls()).isEqualTo(Element.KIM);
    assertThat(Element.THUY.key()).isEqualTo("thuy");
    assertThat(Element.THUY.label()).isEqualTo("Thủy");
  }
}
