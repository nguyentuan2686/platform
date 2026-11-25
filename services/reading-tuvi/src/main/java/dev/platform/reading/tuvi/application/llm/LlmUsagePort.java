package dev.platform.reading.tuvi.application.llm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/** Shared (cross-replica) provider usage and exhaustion state. */
public interface LlmUsagePort {
  int requestsOn(String provider, LocalDate utcDay);

  void recordAttempt(String provider, LocalDate utcDay);

  void recordTokens(
      String provider, LocalDate utcDay, int prompt, int completion, long costMicroUsd);

  long spendMicroUsd(String provider, YearMonth utcMonth);

  /** Keeps the later of the stored and the given instant. */
  void markExhausted(String provider, Instant until);

  Optional<Instant> exhaustedUntil(String provider);
}
