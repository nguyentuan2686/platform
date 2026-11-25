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
  void null_is_empty() {
    assertThat(QuestionNormalizer.normalize(null)).isEmpty();
  }

  @Test
  void fold_removes_diacritics_and_d_stroke() {
    assertThat(QuestionNormalizer.fold("đánh đề Ở ĐÂU")).isEqualTo("danh de o dau");
  }
}
