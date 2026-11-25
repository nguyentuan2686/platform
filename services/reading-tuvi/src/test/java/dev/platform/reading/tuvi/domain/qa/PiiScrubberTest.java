package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiScrubberTest {

  private static final String HIDDEN = "[đã ẩn]";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0912 345 678",
        "+84912345678",
        "0912.345.678",
        "a.b@example.test",
        "12/03/1994",
        "1-3-94",
        "1994031208",
        "ngày 12 tháng 3 năm 1994",
        "8h30",
        "8 giờ 30"
      })
  void each_pii_form_is_replaced(String pii) {
    String out = PiiScrubber.scrub("Của con là " + pii + " đấy");
    assertThat(out).isEqualTo("Của con là " + HIDDEN + " đấy");
  }

  @Test
  void year_and_small_numbers_are_kept() {
    String text = "Năm 2027 con 30 tuổi, có 2 đứa con";
    assertThat(PiiScrubber.scrub(text)).isEqualTo(text);
  }

  @Test
  void several_items_in_one_text() {
    assertThat(PiiScrubber.scrub("gọi 0912345678 hoặc mail x@y.vn"))
        .isEqualTo("gọi " + HIDDEN + " hoặc mail " + HIDDEN);
  }

  @Test
  void null_is_empty() {
    assertThat(PiiScrubber.scrub(null)).isEmpty();
  }
}
