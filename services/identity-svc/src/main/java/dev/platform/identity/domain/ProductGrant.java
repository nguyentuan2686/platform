package dev.platform.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** What a product code grants. The codes are the {@code PaymentSucceeded} event contract. */
public sealed interface ProductGrant {

  record Credits(int n) implements ProductGrant {}

  /** {@code questions} is null for an unlimited pass. */
  record Pass(PassKind kind, Duration duration, Integer questions) implements ProductGrant {

    /** A new pass starts now, or when the latest existing pass ends if that is later. */
    public Instant startsAt(Instant now, Optional<Instant> latestEnd) {
      return latestEnd.filter(end -> end.isAfter(now)).orElse(now);
    }

    public Instant endsAt(Instant startsAt) {
      return startsAt.plus(duration);
    }
  }

  /** Never echoes the offending code. */
  static ProductGrant of(String productCode) {
    if (productCode == null) {
      throw new IllegalArgumentException("Unknown product code");
    }
    return switch (productCode) {
      case "CREDIT_1" -> new Credits(1);
      case "CREDIT_5" -> new Credits(5);
      case "PASS_MONTH" -> new Pass(PassKind.MONTH, Duration.ofDays(30), 2);
      case "PASS_YEAR" -> new Pass(PassKind.YEAR, Duration.ofDays(365), null);
      default -> throw new IllegalArgumentException("Unknown product code");
    };
  }
}
