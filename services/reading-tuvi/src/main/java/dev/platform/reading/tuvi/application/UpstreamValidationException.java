package dev.platform.reading.tuvi.application;

import java.util.List;

/** The chart service rejected the request (400); {@code errors} are copied from its problem. */
public class UpstreamValidationException extends RuntimeException {

  public record FieldError(String field, String message) {}

  private final transient List<FieldError> errors;

  public UpstreamValidationException(List<FieldError> errors) {
    super("Request validation failed");
    this.errors = List.copyOf(errors);
  }

  public List<FieldError> errors() {
    return errors;
  }
}
