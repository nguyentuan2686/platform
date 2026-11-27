package dev.platform.reading.tuvi.domain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.ChartFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimingLinesTest {

  private final Chart chart = ChartFixture.load(); // lunar birth year 1994

  @Test
  void current_decade_then_each_topic_palace_except_menh() {
    assertThat(TimingLines.lines(chart, List.of("Menh", "QuanLoc", "PhucDuc"), 2026))
        .containsExactly(
            "Đại hạn hiện tại (năm 2026): cung Phúc Đức, năm 2019–2028",
            "Đại hạn cung Quan Lộc: năm 2039–2048",
            "Lưu niên vào cung Quan Lộc: năm 2035 (Ất Mão)",
            "Đại hạn cung Phúc Đức: năm 2019–2028 (đang đi)",
            "Lưu niên vào cung Phúc Đức: năm 2033 (Quý Sửu)");
  }

  @Test
  void past_decades_are_marked_and_no_current_decade_before_the_first() {
    assertThat(TimingLines.lines(chart, List.of("Menh", "PhuMau"), 2026))
        .contains("Đại hạn cung Phụ Mẫu: năm 2009–2018 (đã qua)");
    // nominal age 2 is before Mệnh's decade (6-15): no current line
    assertThat(TimingLines.lines(chart, List.of("Menh"), 1995)).isEmpty();
  }

  @Test
  void can_chi_and_flow_year() {
    assertThat(TimingLines.canChi(2031)).isEqualTo("Tân Hợi");
    assertThat(TimingLines.canChi(2026)).isEqualTo("Bính Ngọ");
    assertThat(TimingLines.canChi(1984)).isEqualTo("Giáp Tý");
    assertThat(TimingLines.flowYear("Wu", 2026)).contains(2026);
    assertThat(TimingLines.flowYear("Si", 2026)).contains(2037);
    assertThat(TimingLines.flowYear("Nope", 2026)).isEmpty();
  }
}
