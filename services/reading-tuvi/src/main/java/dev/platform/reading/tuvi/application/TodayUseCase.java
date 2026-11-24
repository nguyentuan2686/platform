package dev.platform.reading.tuvi.application;

import java.time.LocalDate;

public interface TodayUseCase {

  TodayReading today(Birth birth, LocalDate date);
}
