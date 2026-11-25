package dev.platform.payment.application;

/** A bad request value; maps to a 400 problem naming the field (never the value). */
public class OrderValidationException extends RuntimeException {

  private final String field;

  public OrderValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
