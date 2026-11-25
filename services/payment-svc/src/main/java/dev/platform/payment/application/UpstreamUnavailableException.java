package dev.platform.payment.application;

/** A dependency (identity-svc, SePay, PayPal) failed or timed out. The message names no target. */
public class UpstreamUnavailableException extends RuntimeException {

  public UpstreamUnavailableException(String message) {
    super(message);
  }
}
