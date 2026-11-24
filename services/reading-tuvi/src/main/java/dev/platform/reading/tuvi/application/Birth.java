package dev.platform.reading.tuvi.application;

/**
 * Birth data as sent to chart-svc. {@code zone} and {@code calendar} may be null (chart-svc
 * defaults them). {@code toString} is redacted so birth data cannot leak into logs.
 */
public record Birth(String birth, String zone, String gender, String calendar) {

  @Override
  public String toString() {
    return "Birth[redacted]";
  }
}
