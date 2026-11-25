package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MagicLinkPolicyTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  @Test
  void constants() {
    assertThat(MagicLinkPolicy.TTL).isEqualTo(Duration.parse("PT15M"));
    assertThat(MagicLinkPolicy.WINDOW).isEqualTo(Duration.parse("PT15M"));
    assertThat(MagicLinkPolicy.MAX_REQUESTS).isEqualTo(3);
  }

  @Test
  void three_in_a_window_allowed_fourth_denied_allowed_again_at_minute_16() {
    List<Instant> sent = new ArrayList<>();
    for (int minute = 0; minute < 3; minute++) {
      Instant now = T0.plus(Duration.ofMinutes(minute));
      assertThat(MagicLinkPolicy.allows(sent, now)).isTrue();
      sent.add(now);
    }

    assertThat(MagicLinkPolicy.allows(sent, T0.plus(Duration.ofMinutes(3)))).isFalse();
    assertThat(MagicLinkPolicy.allows(sent, T0.plus(Duration.ofMinutes(16)))).isTrue();
  }

  @Test
  void window_start_is_fifteen_minutes_back() {
    assertThat(MagicLinkPolicy.windowStart(T0)).isEqualTo(T0.minus(Duration.ofMinutes(15)));
  }

  @Test
  void expiry_is_ttl_after_now() {
    assertThat(MagicLinkPolicy.expiresAt(T0)).isEqualTo(T0.plus(Duration.ofMinutes(15)));
  }
}
