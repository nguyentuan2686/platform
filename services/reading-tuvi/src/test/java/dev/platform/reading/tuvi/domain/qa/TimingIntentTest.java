package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TimingIntentTest {

  static final int NOW = 2026;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Khi nào tôi gặp được người hợp?",
        "Bao giờ tôi lấy vợ?",
        "Năm nào tôi cưới?",
        "Lúc nào tôi đổi việc được",
        "Tôi lấy chồng năm mấy",
        "Tháng nào tôi có tiền",
        "Tháng mấy thì tôi thăng chức",
        "Bao lâu nữa tôi mua được nhà",
        "Mấy tuổi tôi lập gia đình",
        "Tuổi nào thì tôi có con",
        "Thời điểm nào hợp để khởi nghiệp",
        "Sắp tới tình duyên thế nào",
        "khi nao toi gap duoc nguoi hop",
        "bao gio toi lay vo",
        "BAO GIỜ tôi lấy vợ"
      })
  void timing_words_are_detected(String q) {
    assertThat(TimingIntent.detect(q, NOW).timing()).as(q).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Tình duyên của tôi ra sao",
        "Cung Mệnh của tôi thuộc hành gì",
        "Năm tôi ba mươi tuổi có gì đặc biệt"
      })
  void ordinary_questions_have_no_timing(String q) {
    TimingIntent t = TimingIntent.detect(q, NOW);
    assertThat(t.timing()).as(q).isFalse();
    assertThat(t.targetYear()).as(q).isEmpty();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Năm 2027 công việc thế nào?        | 2027
          Tình duyên năm 2041 ra sao         | 2041
          Năm 2026 tôi có đổi việc không     | 2026
          Năm nay tài lộc thế nào            | 2026
          Năm sau tôi có lấy vợ không        | 2027
          Năm tới sự nghiệp ra sao           | 2027
          Sang năm tôi có nên chuyển nhà     | 2027
          nam sau tinh duyen the nao         | 2027
          nam nay cong viec ra sao           | 2026
          Năm nay hay năm sau tốt hơn        | 2027
          Năm 2025 và năm 2028 khác gì       | 2028
          """)
  void target_year_is_detected(String q, int year) {
    assertThat(TimingIntent.detect(q, NOW).targetYear()).as(q).contains(year);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Năm 2020 tôi đã đổi việc, có đúng không",
        "Năm 2042 tôi ra sao",
        "Số 12345 có ý nghĩa gì",
        "Năm tôi lấy vợ có gì đặc biệt",
        "Năm sáu tuổi tôi thế nào"
      })
  void years_out_of_range_or_lookalikes_are_ignored(String q) {
    assertThat(TimingIntent.detect(q, NOW).targetYear()).as(q).isEmpty();
  }

  @Test
  void none_and_null() {
    assertThat(TimingIntent.NONE.timing()).isFalse();
    assertThat(TimingIntent.NONE.targetYear()).isEmpty();
    assertThat(new TimingIntent(true, null).targetYear()).isEqualTo(Optional.empty());
    assertThat(TimingIntent.detect(null, NOW)).isEqualTo(TimingIntent.NONE);
  }
}
