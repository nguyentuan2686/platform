package dev.platform.payment.application;

import dev.platform.payment.domain.PaymentSucceeded;

public interface PaymentEventsPort {

  /** Appends to the outbox inside the caller's transaction. */
  void publish(PaymentSucceeded event);
}
