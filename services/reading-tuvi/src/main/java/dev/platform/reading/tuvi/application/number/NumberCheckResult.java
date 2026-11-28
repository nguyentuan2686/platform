package dev.platform.reading.tuvi.application.number;

import dev.platform.reading.tuvi.domain.number.NumberScore;
import dev.platform.reading.tuvi.domain.number.Profile;
import java.util.List;

/** Score, the person's mệnh and cung phi, the reading paragraphs and the quota after this check. */
public record NumberCheckResult(
    NumberScore score, Profile profile, List<String> reading, NumberQuota quota) {

  public NumberCheckResult {
    reading = List.copyOf(reading);
  }

  @Override
  public String toString() {
    return "NumberCheckResult[redacted]";
  }
}
