package dev.platform.reading.tuvi.application;

import java.time.LocalDate;

/** Outbound port to the chart service. */
public interface ChartPort {

  /**
   * @throws UpstreamValidationException when the chart service rejects the birth data
   * @throws UpstreamUnavailableException when the chart service cannot answer
   */
  ChartResult chart(Birth birth);

  /** Same failure modes as {@link #chart}. */
  DailyResult daily(Birth birth, LocalDate date);
}
