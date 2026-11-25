package dev.platform.reading.tuvi.application.qa;

/** Result of returning a reading's credit. */
public enum RefundOutcome {
  /** Refunded now or earlier. */
  REFUNDED,
  /** identity-svc has no consume for this reading (404). */
  NOT_CHARGED
}
