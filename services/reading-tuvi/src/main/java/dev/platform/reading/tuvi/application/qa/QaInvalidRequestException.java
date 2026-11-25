package dev.platform.reading.tuvi.application.qa;

/** A 400 on one request field; the value itself is never carried. */
public class QaInvalidRequestException extends RuntimeException {

  private final String field;

  public QaInvalidRequestException(String field) {
    super("Invalid " + field);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
