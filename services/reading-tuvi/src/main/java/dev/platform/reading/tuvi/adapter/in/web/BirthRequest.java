package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.Birth;

/** Body of {@code POST /tuvi/readings/basic}: the chart-svc {@code ChartRequest} fields. */
public record BirthRequest(String birth, String zone, String gender, String calendar) {

  Birth toBirth() {
    return new Birth(birth, zone, gender, calendar);
  }

  @Override
  public String toString() {
    return "BirthRequest[redacted]";
  }
}
