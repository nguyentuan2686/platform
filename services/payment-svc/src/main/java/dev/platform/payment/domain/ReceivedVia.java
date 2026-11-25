package dev.platform.payment.domain;

/** How a bank transaction reached us. */
public enum ReceivedVia {
  WEBHOOK,
  RECONCILE
}
