package dev.platform.reading.tuvi.application;

import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.Level;
import java.time.LocalDate;
import java.util.List;

/** A rendered "Hôm nay" reading: four areas in {@link Area} order between opening and closing. */
public record TodayReading(
    LocalDate date,
    String lunar,
    String dayCanChi,
    String character,
    String opening,
    List<AreaLine> areas,
    String closing) {

  public record AreaLine(Area area, String label, Level level, String line) {}
}
