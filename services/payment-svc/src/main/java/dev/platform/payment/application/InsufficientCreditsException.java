package dev.platform.payment.application;

/** identity-svc refused a reversal: the credits of the purchase were already used. */
public class InsufficientCreditsException extends RuntimeException {

  public InsufficientCreditsException() {
    super("credits already used");
  }
}
