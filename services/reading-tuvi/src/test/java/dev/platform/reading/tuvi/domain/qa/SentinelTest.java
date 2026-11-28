package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SentinelTest {

  @ParameterizedTest(name = "{1} <- {0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          NGOAI_PHAM_VI               | OFF_TOPIC
          **NGOAI_PHAM_VI**           | OFF_TOPIC
          `KHONG_DU_TU_LIEU`          | INSUFFICIENT
          KHONG_DU_TU_LIEU.           | INSUFFICIENT
          KHONG_DU_TIEU_LIEU          | INSUFFICIENT
          NGOAI_PHAM_VY               | OFF_TOPIC
          """)
  void detects_sentinels_and_near_misspellings(String answer, RefusalReason reason) {
    // Groq gpt-oss wrote KHONG_DU_TIEU_LIEU (2 of 3 runs, 2026-11-28): still a refusal
    assertThat(Sentinel.detect(answer)).contains(reason);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Con ơi, năm 2027 lưu niên ở cung Quan Lộc, đường nghề của con có nhiều đổi thay.",
        "KHONG phải lo, con ạ.",
        "NGOAI ra, con nên giữ sức."
      })
  void prose_is_not_a_sentinel(String answer) {
    assertThat(Sentinel.detect(answer)).isEmpty();
  }
}
