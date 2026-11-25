package dev.platform.identity.application;

/** A credit operation refused by a business rule; {@code reason} is a snake_case code. */
public class CreditConflictException extends RuntimeException {

  private final String reason;

  public CreditConflictException(String reason) {
    super(reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
