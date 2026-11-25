package dev.platform.reading.tuvi.application.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

  @Test
  void schedule_is_5m_15m_30m_1h_2h_then_every_2h() {
    assertThat(RetryBackoff.next(1)).isEqualTo(Duration.ofMinutes(5));
    assertThat(RetryBackoff.next(2)).isEqualTo(Duration.ofMinutes(15));
    assertThat(RetryBackoff.next(3)).isEqualTo(Duration.ofMinutes(30));
    assertThat(RetryBackoff.next(4)).isEqualTo(Duration.ofHours(1));
    assertThat(RetryBackoff.next(5)).isEqualTo(Duration.ofHours(2));
    assertThat(RetryBackoff.next(6)).isEqualTo(Duration.ofHours(2));
    assertThat(RetryBackoff.next(40)).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void zero_or_negative_attempts_use_the_first_delay() {
    assertThat(RetryBackoff.next(0)).isEqualTo(Duration.ofMinutes(5));
    assertThat(RetryBackoff.next(-3)).isEqualTo(Duration.ofMinutes(5));
  }
}
