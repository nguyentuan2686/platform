package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.number.NumberCheckCommand;
import dev.platform.reading.tuvi.domain.number.Gender;
import dev.platform.reading.tuvi.domain.number.InvalidNumberException;
import dev.platform.reading.tuvi.domain.number.NumberKind;
import dev.platform.reading.tuvi.domain.number.Vehicle;
import java.util.Locale;

/**
 * Body of {@code POST /tuvi/number-check}. Values are parsed by hand: a bad kind, vehicle or number
 * is {@code invalid_number}, a bad year or gender {@code invalid_birth}. {@code toString} is
 * redacted.
 */
public record NumberCheckRequest(
    String kind,
    String vehicle,
    String number,
    Integer birthYear,
    Boolean beforeTet,
    String gender) {

  NumberCheckCommand toCommand(String bearer, String clientIp) {
    NumberKind k = parse(NumberKind.class, kind, "invalid_number");
    Vehicle v = k == NumberKind.PLATE ? parse(Vehicle.class, vehicle, "invalid_number") : null;
    return new NumberCheckCommand(
        bearer,
        clientIp,
        k,
        v,
        number,
        birthYear,
        Boolean.TRUE.equals(beforeTet),
        parse(Gender.class, gender, "invalid_birth"));
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String reason) {
    if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
      throw new InvalidNumberException(reason);
    }
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidNumberException(reason);
    }
  }

  @Override
  public String toString() {
    return "NumberCheckRequest[redacted]";
  }
}
