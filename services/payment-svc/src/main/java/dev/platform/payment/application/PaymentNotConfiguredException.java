package dev.platform.payment.application;

/** The receiving bank account is not configured, so bank-transfer orders cannot be offered. */
public class PaymentNotConfiguredException extends RuntimeException {

  public PaymentNotConfiguredException() {
    super("Bank transfer is not available");
  }
}
