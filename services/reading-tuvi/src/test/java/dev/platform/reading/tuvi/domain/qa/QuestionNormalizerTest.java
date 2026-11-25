package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class QuestionNormalizerTest {

  @Test
  void trims_collapses_lowercases_and_strips_trailing_punctuation() {
    assertThat(QuestionNormalizer.normalize("  Năm nay   TÌNH DUYÊN thế nào??  "))
        .isEqualTo("năm nay tình duyên thế nào");
  }

  @Test
  void composes_to_nfc_and_strips_ellipsis_and_newlines() {
    String decomposed = Normalizer.normalize("Tài lộc\n ra sao…!", Normalizer.Form.NFD);
    assertThat(QuestionNormalizer.normalize(decomposed)).isEqualTo("tài lộc ra sao");
  }

  @Test
  void clean_folds_unicode_tricks_and_strips_delimiters_but_keeps_newlines() {
    assertThat(QuestionNormalizer.clean(" Ａb c\td​e️ `x` <<<y>>> іо\r\nz\u0007 "))
        .isEqualTo("Ab c de x y io\nz");
  }

  @Test
  void match_form_unifies_tone_placement_and_in_word_punctuation() {
    assertThat(QuestionNormalizer.matchForm("Phải HOÁ giải, đại hoạ, ra toà, khoẻ, thuỷ, bỏ-qua"))
        .isEqualTo("phải hóa giải, đại họa, ra tòa, khỏe, thủy, bỏ qua");
    // a tone on "a" before a final consonant is the only placement and stays put
    assertThat(QuestionNormalizer.matchForm("hoàn toàn")).isEqualTo("hoàn toàn");
  }

  @Test
  void null_is_empty() {
    assertThat(QuestionNormalizer.normalize(null)).isEmpty();
  }

  @Test
  void fold_removes_diacritics_and_d_stroke() {
    assertThat(QuestionNormalizer.fold("đánh đề Ở ĐÂU")).isEqualTo("danh de o dau");
  }
}
