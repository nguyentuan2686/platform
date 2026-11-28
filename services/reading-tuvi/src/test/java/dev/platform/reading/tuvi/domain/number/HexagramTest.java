package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HexagramTest {

  @Test
  void table_has_64_distinct_trigram_pairs_and_king_wen_ids() {
    assertThat(Hexagram.all()).hasSize(64);
    Set<String> pairs = new HashSet<>();
    Set<Integer> ids = new HashSet<>();
    for (Hexagram h : Hexagram.all()) {
      pairs.add(h.upper() + "/" + h.lower());
      ids.add(h.id());
      assertThat(h.name()).isNotBlank();
    }
    assertThat(pairs).hasSize(64);
    assertThat(ids).hasSize(64).allMatch(i -> i >= 1 && i <= 64);
  }

  @Test
  void known_hexagrams() {
    Hexagram thai = Hexagram.of(Trigram.KHON, Trigram.CAN_THIEN);
    assertThat(thai.id()).isEqualTo(11);
    assertThat(thai.name()).isEqualTo("Địa Thiên Thái");
    assertThat(thai.fortune()).isEqualTo(Hexagram.Fortune.CAT);
    assertThat(Hexagram.of(Trigram.LY, Trigram.CHAN).id()).isEqualTo(21);
    assertThat(Hexagram.of(Trigram.CAN_THIEN, Trigram.CAN_THIEN).name()).isEqualTo("Thuần Càn");
    assertThat(Hexagram.of(Trigram.CAN_THIEN, Trigram.KHON).fortune())
        .isEqualTo(Hexagram.Fortune.HUNG);
    assertThat(Hexagram.of(Trigram.KHAM, Trigram.LY).id()).isEqualTo(63);
    assertThat(Hexagram.of(Trigram.LY, Trigram.KHAM).id()).isEqualTo(64);
  }

  @Test
  void every_fortune_class_is_used() {
    Set<Hexagram.Fortune> used = new HashSet<>();
    Hexagram.all().forEach(h -> used.add(h.fortune()));
    assertThat(used).containsExactlyInAnyOrder(Hexagram.Fortune.values());
  }

  @Test
  void trigrams_follow_tien_thien_numbering_and_bat_trach_groups() {
    assertThat(Trigram.ofNumber(1)).isEqualTo(Trigram.CAN_THIEN);
    assertThat(Trigram.ofNumber(2)).isEqualTo(Trigram.DOAI);
    assertThat(Trigram.ofNumber(3)).isEqualTo(Trigram.LY);
    assertThat(Trigram.ofNumber(4)).isEqualTo(Trigram.CHAN);
    assertThat(Trigram.ofNumber(5)).isEqualTo(Trigram.TON);
    assertThat(Trigram.ofNumber(6)).isEqualTo(Trigram.KHAM);
    assertThat(Trigram.ofNumber(7)).isEqualTo(Trigram.CAN_SON);
    assertThat(Trigram.ofNumber(8)).isEqualTo(Trigram.KHON);
    assertThat(Trigram.KHAM.east()).isTrue();
    assertThat(Trigram.CHAN.east()).isTrue();
    assertThat(Trigram.TON.east()).isTrue();
    assertThat(Trigram.LY.east()).isTrue();
    assertThat(Trigram.CAN_THIEN.east()).isFalse();
    assertThat(Trigram.KHON.east()).isFalse();
    assertThat(Trigram.CAN_SON.east()).isFalse();
    assertThat(Trigram.DOAI.east()).isFalse();
    assertThat(Trigram.CAN_SON.label()).isEqualTo("Cấn");
  }
}
