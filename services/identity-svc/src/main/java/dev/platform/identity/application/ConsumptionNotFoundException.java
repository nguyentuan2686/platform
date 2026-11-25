package dev.platform.identity.application;

/** No consume was ever recorded for the reading. */
public class ConsumptionNotFoundException extends RuntimeException {

  public ConsumptionNotFoundException() {
    super("Consumption not found");
  }
}
