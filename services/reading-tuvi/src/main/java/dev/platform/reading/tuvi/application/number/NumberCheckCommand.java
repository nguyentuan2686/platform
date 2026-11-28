package dev.platform.reading.tuvi.application.number;

import dev.platform.reading.tuvi.domain.number.Gender;
import dev.platform.reading.tuvi.domain.number.NumberKind;
import dev.platform.reading.tuvi.domain.number.Vehicle;

/**
 * One number check. {@code clientIp} may be null (no IP limit then); {@code vehicle} is required
 * for a plate. {@link #toString()} is redacted: it never prints the number, year or IP.
 */
public record NumberCheckCommand(
    String bearer,
    String clientIp,
    NumberKind kind,
    Vehicle vehicle,
    String number,
    Integer birthYear,
    boolean beforeTet,
    Gender gender) {

  @Override
  public String toString() {
    return "NumberCheckCommand[kind=" + kind + ", redacted]";
  }
}
