package dev.platform.reading.tuvi.application.qa;

import java.time.Duration;

/**
 * {@code QA_FREE_DAILY_PER_SUBJECT} (0 disables free chat), {@code QA_FREE_GLOBAL_DAILY_CAP},
 * {@code QA_RETRY_WINDOW} and {@code QA_CONSUME_REFUND_GRACE}: how long a reading whose consume
 * call failed waits before its compensating refund, so a consume identity-svc is still committing
 * is refunded rather than reported as never charged.
 */
public record QaSettings(
    int freeDailyPerSubject,
    int freeGlobalDailyCap,
    Duration retryWindow,
    Duration consumeRefundGrace) {

  public static final Duration DEFAULT_CONSUME_REFUND_GRACE = Duration.ofMinutes(2);

  public QaSettings(int freeDailyPerSubject, int freeGlobalDailyCap, Duration retryWindow) {
    this(freeDailyPerSubject, freeGlobalDailyCap, retryWindow, DEFAULT_CONSUME_REFUND_GRACE);
  }
}
