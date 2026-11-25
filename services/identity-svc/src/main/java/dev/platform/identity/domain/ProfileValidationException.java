package dev.platform.identity.domain;

/** A profile field is invalid. The message never echoes the offending value. */
public class ProfileValidationException extends RuntimeException {

  private final String field;

  public ProfileValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
