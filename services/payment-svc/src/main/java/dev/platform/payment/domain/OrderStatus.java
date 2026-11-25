package dev.platform.payment.domain;

/** Persisted order states. {@code EXPIRED} is display only, see {@link Order#viewStatus}. */
public enum OrderStatus {
  PENDING,
  PAID,
  REFUND_REQUESTED,
  REFUNDED
}
