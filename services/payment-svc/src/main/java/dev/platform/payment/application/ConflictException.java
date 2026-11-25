package dev.platform.payment.application;

/** A request refused because of the current state; {@code reason} is a snake_case code. */
public class ConflictException extends RuntimeException {

  private final String reason;

  public ConflictException(String reason) {
    super(reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
