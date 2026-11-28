package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CungPhiTest {

  private static final int NOW = 2026;

  private static Profile p(int year, boolean beforeTet, Gender g) {
    return Profile.of(year, beforeTet, g, NOW);
  }

  @Test
  void required_cases() {
    Profile m1990 = p(1990, false, Gender.MALE);
    assertThat(m1990.cungPhi()).isEqualTo(1);
    assertThat(m1990.cungPhiName()).isEqualTo("Khảm");
    assertThat(m1990.eastGroup()).isTrue();
    assertThat(m1990.napAm()).isEqualTo("Lộ Bàng Thổ");
    assertThat(m1990.element()).isEqualTo(Element.THO);
    assertThat(m1990.canChi()).isEqualTo("Canh Ngọ");
    assertThat(m1990.lunarYear()).isEqualTo(1990);

    Profile f1990 = p(1990, false, Gender.FEMALE);
    assertThat(f1990.cungPhi()).isEqualTo(8);
    assertThat(f1990.cungPhiName()).isEqualTo("Cấn");
    assertThat(f1990.eastGroup()).isFalse();

    assertThat(p(2000, false, Gender.MALE).cungPhiName()).isEqualTo("Ly");
    assertThat(p(2000, false, Gender.MALE).cungPhi()).isEqualTo(9);
    assertThat(p(2000, false, Gender.FEMALE).cungPhiName()).isEqualTo("Càn");
    assertThat(p(2000, false, Gender.FEMALE).cungPhi()).isEqualTo(6);
  }

  @Test
  void before_tet_uses_the_previous_lunar_year() {
    Profile p = p(2000, true, Gender.MALE);
    assertThat(p.lunarYear()).isEqualTo(1999);
    assertThat(p.canChi()).isEqualTo("Kỷ Mão");
    assertThat(p.cungPhiName()).isEqualTo("Khảm");
  }

  @Test
  void five_maps_to_khon_for_men_and_can_for_women() {
    // 1986: r = 8 + 6 = 14 -> 5; male 10 - 5 = 5 -> Khôn 2
    assertThat(p(1986, false, Gender.MALE).cungPhi()).isEqualTo(2);
    assertThat(p(1986, false, Gender.MALE).cungPhiName()).isEqualTo("Khôn");
    // 2008: r = 8; female 6 + 8 = 14 -> 5 -> Cấn 8
    assertThat(p(2008, false, Gender.FEMALE).cungPhi()).isEqualTo(8);
  }

  @Test
  void edge_years() {
    // 1930: r = 3; male 7 Đoài, female 8 Cấn
    assertThat(p(1930, false, Gender.MALE).cungPhiName()).isEqualTo("Đoài");
    assertThat(p(1930, false, Gender.FEMALE).cungPhiName()).isEqualTo("Cấn");
    // 1999: r = 18 -> 9; male 1 Khảm, female 14 -> 5 -> Cấn
    assertThat(p(1999, false, Gender.MALE).cungPhiName()).isEqualTo("Khảm");
    assertThat(p(1999, false, Gender.FEMALE).cungPhiName()).isEqualTo("Cấn");
    // 2001: r = 1; male 8 Cấn, female 7 Đoài
    assertThat(p(2001, false, Gender.MALE).cungPhiName()).isEqualTo("Cấn");
    assertThat(p(2001, false, Gender.FEMALE).cungPhiName()).isEqualTo("Đoài");
    // 2009: r = 9; male 9 - 9 = 0 -> 9 Ly
    assertThat(p(2009, false, Gender.MALE).cungPhiName()).isEqualTo("Ly");
    // 2026: r = 8; male 1 Khảm, female 14 -> 5 -> Cấn
    assertThat(p(2026, false, Gender.MALE).cungPhiName()).isEqualTo("Khảm");
    assertThat(p(2026, false, Gender.FEMALE).cungPhiName()).isEqualTo("Cấn");
    // 1930 before Tết is lunar 1929 Kỷ Tỵ
    assertThat(p(1930, true, Gender.MALE).lunarYear()).isEqualTo(1929);
  }

  @Test
  void out_of_range_years_are_invalid_birth() {
    for (int year : new int[] {1929, NOW + 1}) {
      assertThatThrownBy(() -> p(year, false, Gender.MALE))
          .isInstanceOf(InvalidNumberException.class)
          .extracting(e -> ((InvalidNumberException) e).reason())
          .isEqualTo("invalid_birth");
    }
    assertThatThrownBy(() -> Profile.of(1990, false, null, NOW))
        .isInstanceOf(InvalidNumberException.class);
  }

  @Test
  void east_group_is_kham_chan_ton_ly() {
    assertThat(CungPhi.isEast(1)).isTrue();
    assertThat(CungPhi.isEast(3)).isTrue();
    assertThat(CungPhi.isEast(4)).isTrue();
    assertThat(CungPhi.isEast(9)).isTrue();
    assertThat(CungPhi.isEast(2)).isFalse();
    assertThat(CungPhi.isEast(6)).isFalse();
    assertThat(CungPhi.isEast(7)).isFalse();
    assertThat(CungPhi.isEast(8)).isFalse();
  }
}
