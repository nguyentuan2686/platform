package dev.platform.reading.tuvi.application.qa;

/** A 409 with a snake_case {@code reason}. */
public class QaConflictException extends RuntimeException {

  private final String reason;

  public QaConflictException(String reason) {
    super("Q&A conflict: " + reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
