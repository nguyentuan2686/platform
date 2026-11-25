package dev.platform.reading.tuvi.application.qa;

/** Lifecycle of a {@code qa_reading}. */
public enum QaStatus {
  PENDING,
  ANSWERED,
  RETRYING,
  REFUND_PENDING,
  REFUNDED,
  REFUSED,
  FAILED
}
