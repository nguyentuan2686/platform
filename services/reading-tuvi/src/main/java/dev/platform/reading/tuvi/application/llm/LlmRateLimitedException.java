package dev.platform.reading.tuvi.application.llm;

import java.time.Duration;
import java.util.Optional;

public class LlmRateLimitedException extends RuntimeException {
  private final transient Optional<Duration> retryAfter;

  public LlmRateLimitedException(Optional<Duration> retryAfter) {
    super("llm provider rate limited");
    this.retryAfter = retryAfter;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
