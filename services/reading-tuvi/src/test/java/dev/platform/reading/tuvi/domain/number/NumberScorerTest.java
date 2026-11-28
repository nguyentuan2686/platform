package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NumberScorerTest {

  private static final Profile THO_KHAM = Profile.of(1990, false, Gender.MALE, 2026);

  @Test
  void digit_elements() {
    assertThat(NumberScorer.digitElement('0')).isEqualTo(Element.THUY);
    assertThat(NumberScorer.digitElement('1')).isEqualTo(Element.THUY);
    assertThat(NumberScorer.digitElement('2')).isEqualTo(Element.THO);
    assertThat(NumberScorer.digitElement('5')).isEqualTo(Element.THO);
    assertThat(NumberScorer.digitElement('8')).isEqualTo(Element.THO);
    assertThat(NumberScorer.digitElement('3')).isEqualTo(Element.MOC);
    assertThat(NumberScorer.digitElement('4')).isEqualTo(Element.MOC);
    assertThat(NumberScorer.digitElement('6')).isEqualTo(Element.KIM);
    assertThat(NumberScorer.digitElement('7')).isEqualTo(Element.KIM);
    assertThat(NumberScorer.digitElement('9')).isEqualTo(Element.HOA);
  }

  @Test
  void number_element_is_the_most_frequent_and_a_tie_takes_the_last_tied_digit() {
    assertThat(NumberScorer.numberElement("666")).isEqualTo(Element.KIM);
    // Kim 2 (6, 7), Thổ 2 (2, 5): the last digit among them is 5 -> Thổ
    assertThat(NumberScorer.numberElement("6275")).isEqualTo(Element.THO);
    // same counts, other order: the last tied digit is 7 -> Kim
    assertThat(NumberScorer.numberElement("2567")).isEqualTo(Element.KIM);
    // 9 is last but Hỏa is not tied for the maximum
    assertThat(NumberScorer.numberElement("33449")).isEqualTo(Element.MOC);
  }

  @Test
  void element_points() {
    // number generates mệnh (Hỏa sinh Thổ)
    assertThat(NumberScorer.elementPoints(Element.HOA, Element.THO)).isEqualTo(3.0);
    assertThat(NumberScorer.elementRelation(Element.HOA, Element.THO)).isEqualTo("sinh_nhap");
    assertThat(NumberScorer.elementPoints(Element.THO, Element.THO)).isEqualTo(2.5);
    assertThat(NumberScorer.elementRelation(Element.THO, Element.THO)).isEqualTo("tuong_hop");
    // mệnh controls number (Thổ khắc Thủy)
    assertThat(NumberScorer.elementPoints(Element.THUY, Element.THO)).isEqualTo(1.5);
    assertThat(NumberScorer.elementRelation(Element.THUY, Element.THO)).isEqualTo("khac_xuat");
    // mệnh generates number (Thổ sinh Kim)
    assertThat(NumberScorer.elementPoints(Element.KIM, Element.THO)).isEqualTo(1.0);
    assertThat(NumberScorer.elementRelation(Element.KIM, Element.THO)).isEqualTo("sinh_xuat");
    // number controls mệnh (Mộc khắc Thổ)
    assertThat(NumberScorer.elementPoints(Element.MOC, Element.THO)).isEqualTo(0.0);
    assertThat(NumberScorer.elementRelation(Element.MOC, Element.THO)).isEqualTo("khac_nhap");
  }

  @Test
  void hexagram_from_the_two_halves() {
    // "12" -> 3 Ly above; "345" -> 12 mod 8 = 4 Chấn below -> Hỏa Lôi Phệ Hạp
    Hexagram h = NumberScorer.hexagram("12345");
    assertThat(h.upper()).isEqualTo(Trigram.LY);
    assertThat(h.lower()).isEqualTo(Trigram.CHAN);
    assertThat(h.id()).isEqualTo(21);
    assertThat(h.name()).isEqualTo("Hỏa Lôi Phệ Hạp");
    // sums 8 and 16: both 0 mod 8 -> Khôn over Khôn
    assertThat(NumberScorer.hexagram("4497").upper()).isEqualTo(Trigram.KHON);
    assertThat(NumberScorer.hexagram("4497").lower()).isEqualTo(Trigram.KHON);
  }

  @Test
  void yin_yang() {
    assertThat(NumberScorer.yinYangPoints("0123456789")).isEqualTo(1.5);
    // 7 even / 3 odd: imbalance 4 <= ceil(4) -> 0.75
    assertThat(NumberScorer.yinYangPoints("0224466135")).isEqualTo(0.75);
    // 8 even / 2 odd: imbalance 6 -> 0
    assertThat(NumberScorer.yinYangPoints("0224466135".replace('3', '8'))).isEqualTo(0.0);
    assertThat(NumberScorer.yinYangPoints("0224466881")).isEqualTo(0.0);
    // odd length: off by one digit counts as balanced
    assertThat(NumberScorer.yinYangPoints("12345")).isEqualTo(1.5);
    assertThat(NumberScorer.yinYangKey(1.5)).isEqualTo("can_bang");
    assertThat(NumberScorer.yinYangKey(0.75)).isEqualTo("hoi_lech");
    assertThat(NumberScorer.yinYangKey(0.0)).isEqualTo("lech");
  }

  @Test
  void cung_phi_points() {
    Hexagram both = Hexagram.of(Trigram.KHAM, Trigram.LY);
    Hexagram one = Hexagram.of(Trigram.CAN_SON, Trigram.KHAM);
    Hexagram none = Hexagram.of(Trigram.CAN_THIEN, Trigram.KHON);
    assertThat(NumberScorer.cungPhiPoints(both, true)).isEqualTo(1.5);
    assertThat(NumberScorer.cungPhiPoints(one, true)).isEqualTo(0.75);
    assertThat(NumberScorer.cungPhiPoints(none, true)).isEqualTo(0.0);
    assertThat(NumberScorer.cungPhiPoints(none, false)).isEqualTo(1.5);
  }

  @Test
  void nut() {
    assertThat(NumberScorer.nut("45")).isEqualTo(9);
    assertThat(NumberScorer.nut("55")).isEqualTo(0);
    assertThat(NumberScorer.nutPoints(9)).isEqualTo(0.5);
    assertThat(NumberScorer.nutPoints(8)).isEqualTo(0.25);
    assertThat(NumberScorer.nutPoints(7)).isEqualTo(0.25);
    assertThat(NumberScorer.nutPoints(6)).isEqualTo(0.0);
    assertThat(NumberScorer.nutPoints(0)).isEqualTo(0.0);
    assertThat(NumberScorer.nutKey(9)).isEqualTo("cao");
    assertThat(NumberScorer.nutKey(7)).isEqualTo("vua");
    assertThat(NumberScorer.nutKey(8)).isEqualTo("vua");
    assertThat(NumberScorer.nutKey(0)).isEqualTo("thap");
  }

  @Test
  void folk_pairs_list() {
    assertThat(FolkPairs.GOOD)
        .contains("68", "86", "39", "79", "38", "78", "66", "88", "99", "6789");
    assertThat(FolkPairs.AVOID).contains("49", "53");
  }

  @Test
  void folk_pairs_points() {
    assertThat(NumberScorer.pairsPoints("1020")).isEqualTo(0.5);
    assertThat(NumberScorer.pairsPoints("1680")).isEqualTo(0.75);
    // overlapping occurrences each count: 686 has 68 and 86
    assertThat(NumberScorer.pairsPoints("686")).isEqualTo(1.0);
    assertThat(NumberScorer.pairsPoints("68686")).isEqualTo(1.0);
    assertThat(NumberScorer.pairsPoints("1490")).isEqualTo(0.25);
    assertThat(NumberScorer.pairsPoints("4953")).isEqualTo(0.0);
    assertThat(NumberScorer.pairsPoints("495349")).isEqualTo(0.0);
    assertThat(NumberScorer.pairsPoints("6849")).isEqualTo(0.5);
    assertThat(NumberScorer.goodFound("13968")).containsExactly("39", "68");
    assertThat(NumberScorer.avoidFound("1490")).containsExactly("49");
  }

  @Test
  void rounding_and_bands() {
    assertThat(NumberScorer.round(9.25)).isEqualTo(9.5);
    assertThat(NumberScorer.round(7.75)).isEqualTo(8.0);
    assertThat(NumberScorer.round(6.2)).isEqualTo(6.0);
    assertThat(NumberScorer.round(6.75)).isEqualTo(7.0);
    assertThat(Band.of(10.0)).isEqualTo(Band.RAT_HOP);
    assertThat(Band.of(8.0)).isEqualTo(Band.RAT_HOP);
    assertThat(Band.of(7.5)).isEqualTo(Band.KHA_HOP);
    assertThat(Band.of(6.5)).isEqualTo(Band.KHA_HOP);
    assertThat(Band.of(6.0)).isEqualTo(Band.BINH_THUONG);
    assertThat(Band.of(5.0)).isEqualTo(Band.BINH_THUONG);
    assertThat(Band.of(4.5)).isEqualTo(Band.CHUA_HOP);
    assertThat(Band.of(0.0)).isEqualTo(Band.CHUA_HOP);
    assertThat(Band.RAT_HOP.key()).isEqualTo("rat_hop");
    assertThat(Band.RAT_HOP.label()).isEqualTo("Rất hợp");
    assertThat(Band.CHUA_HOP.label()).isEqualTo("Chưa hợp lắm");
  }

  /**
   * Golden: SIM 0912345678 for a man born 1990 (Canh Ngọ, Lộ Bàng Thổ, cung Khảm, Đông tứ mệnh).
   *
   * <ul>
   *   <li>Ngũ hành: 0,1 Thủy (2); 9 Hỏa (1); 2,5,8 Thổ (3); 3,4 Mộc (2); 6,7 Kim (2). Thổ has the
   *       most digits, the number is Thổ, the same as the mệnh: tương hợp 2.5.
   *   <li>Quẻ: halves 09123 / 45678; 15 mod 8 = 7 Cấn above, 30 mod 8 = 6 Khảm below: Sơn Thủy Mông
   *       (4), bình 1.25.
   *   <li>Âm dương: 5 even (0,2,4,6,8), 5 odd: imbalance 0 -> 1.5.
   *   <li>Cung phi: Cấn is West, Khảm is East; the man is East: one trigram -> 0.75.
   *   <li>Nút: 0+9+1+...+8 = 45, 5 nút -> 0.
   *   <li>Cặp số: pairs 09 91 12 23 34 45 56 67 78; 78 is good: 0.5 + 0.25 = 0.75.
   *   <li>Total 2.5 + 1.25 + 1.5 + 0.75 + 0 + 0.75 = 6.75 -> 7.0, Khá hợp.
   * </ul>
   */
  @Test
  void golden_sim() {
    NumberScore s =
        NumberScorer.score(NumberInput.parse(NumberKind.SIM, null, "0912345678"), THO_KHAM);
    assertThat(s.criteria())
        .extracting(Criterion::key)
        .containsExactly("element", "hexagram", "yinyang", "cungphi", "nut", "pairs");
    assertThat(s.criteria())
        .extracting(Criterion::points)
        .containsExactly(2.5, 1.25, 1.5, 0.75, 0.0, 0.75);
    assertThat(s.criteria())
        .extracting(Criterion::max)
        .containsExactly(3.0, 2.5, 1.5, 1.5, 0.5, 1.0);
    assertThat(s.criteria())
        .extracting(Criterion::readingKey)
        .containsExactly(
            "so:element=tuong_hop",
            "so:que=4",
            "so:amduong=can_bang",
            "so:cungphi=hop_mot",
            "so:nut=thap",
            null);
    assertThat(s.criteria())
        .extracting(Criterion::detail)
        .containsExactly(
            "Thổ hòa Thổ", "Sơn Thủy Mông", "5 chẵn / 5 lẻ", "Hợp 1 quái", "5 nút", "78");
    assertThat(s.total()).isEqualTo(7.0);
    assertThat(s.band()).isEqualTo(Band.KHA_HOP);
    assertThat(s.hexagram().id()).isEqualTo(4);
    assertThat(s.numberElement()).isEqualTo(Element.THO);
    assertThat(s.goodPairs()).isEqualTo(List.of("78"));
    assertThat(s.avoidPairs()).isEmpty();
  }

  @Test
  void criterion_details_name_the_relation_and_never_the_number() {
    Profile kim = Profile.of(1984, false, Gender.MALE, 2026);
    NumberScore s = NumberScorer.score(NumberInput.parse(NumberKind.BANK, null, "999999"), kim);
    // Hỏa number, Kim mệnh: number controls mệnh
    assertThat(s.criteria().get(0).detail()).isEqualTo("Hỏa khắc Kim");
    assertThat(s.criteria().get(0).points()).isEqualTo(0.0);
    assertThat(s.criteria()).noneMatch(c -> c.detail().contains("999999"));
    assertThat(s.criteria().get(5).detail()).isEqualTo("99");
    assertThat(
            NumberScorer.score(NumberInput.parse(NumberKind.BANK, null, "102030"), kim)
                .criteria()
                .get(5)
                .detail())
        .isEqualTo("Không có");
  }
}
