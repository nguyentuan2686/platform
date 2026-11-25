package dev.platform.identity.domain;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * Birth date-time, IANA zone and gender, in the syntax chart-svc accepts. Normalised on creation
 * (zone defaulted, seconds dropped when zero) so equal births compare equal. {@code toString} is
 * redacted so birth data cannot leak into logs.
 */
public record BirthData(String birth, String zone, String gender) {

  public static final String DEFAULT_ZONE = "Asia/Ho_Chi_Minh";

  private static final Pattern BIRTH =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?$");

  public BirthData {
    if (birth == null || !BIRTH.matcher(birth).matches()) {
      throw new ProfileValidationException("birth.birth", "birth must be yyyy-MM-ddTHH:mm[:ss]");
    }
    try {
      birth = LocalDateTime.parse(birth).toString();
    } catch (DateTimeException e) {
      throw new ProfileValidationException("birth.birth", "birth is not a valid date and time");
    }
    if (zone == null || zone.isBlank()) {
      zone = DEFAULT_ZONE;
    }
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new ProfileValidationException("birth.zone", "zone is not a known IANA zone");
    }
    if (!"male".equals(gender) && !"female".equals(gender)) {
      throw new ProfileValidationException("birth.gender", "gender must be male or female");
    }
  }

  @Override
  public String toString() {
    return "BirthData[redacted]";
  }
}
