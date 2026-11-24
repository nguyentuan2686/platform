package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.Birth;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Body of {@code POST /tuvi/readings/today}: the birth fields plus the solar {@code date}. */
public record TodayRequest(
    String birth,
    String zone,
    String gender,
    String calendar,
    @NotNull(message = "date is required") LocalDate date) {

  Birth toBirth() {
    return new Birth(birth, zone, gender, calendar);
  }

  @Override
  public String toString() {
    return "TodayRequest[redacted]";
  }
}
