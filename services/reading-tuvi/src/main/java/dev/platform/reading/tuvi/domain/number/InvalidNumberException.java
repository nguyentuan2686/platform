package dev.platform.reading.tuvi.domain.number;

/**
 * A number or birth input that cannot be checked. {@code reason} is {@code invalid_number} or
 * {@code invalid_birth}; the message never carries the input.
 */
public class InvalidNumberException extends RuntimeException {

  private final String reason;

  public InvalidNumberException(String reason) {
    super(reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
