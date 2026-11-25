package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductGrantTest {

  @Test
  void credit_packs() {
    assertThat(ProductGrant.of("CREDIT_1")).isEqualTo(new ProductGrant.Credits(1));
    assertThat(ProductGrant.of("CREDIT_5")).isEqualTo(new ProductGrant.Credits(5));
  }

  @Test
  void passes() {
    assertThat(ProductGrant.of("PASS_MONTH"))
        .isEqualTo(new ProductGrant.Pass(PassKind.MONTH, Duration.ofDays(30), 2));
    assertThat(ProductGrant.of("PASS_YEAR"))
        .isEqualTo(new ProductGrant.Pass(PassKind.YEAR, Duration.ofDays(365), null));
  }

  @Test
  void unknown_code_is_rejected_without_echoing_it() {
    assertThatThrownBy(() -> ProductGrant.of("CREDIT_9"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProductGrant.of(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_new_pass_starts_now_or_when_the_latest_pass_ends() {
    ProductGrant.Pass month = (ProductGrant.Pass) ProductGrant.of("PASS_MONTH");
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    Instant later = Instant.parse("2026-09-01T00:00:00Z");

    assertThat(month.startsAt(now, Optional.empty())).isEqualTo(now);
    assertThat(month.startsAt(now, Optional.of(later))).isEqualTo(later);
    assertThat(month.startsAt(later, Optional.of(now))).isEqualTo(later);
    assertThat(month.endsAt(later)).isEqualTo(later.plus(Duration.ofDays(30)));
  }
}
