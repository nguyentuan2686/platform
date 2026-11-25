package dev.platform.reading.tuvi.application.qa;

import java.time.Duration;
import java.util.List;

/**
 * Delay before the next model attempt of a paid question, by the number of attempts made so far: 5
 * min, 15 min, 30 min, 1 h, 2 h, then 2 h for every later attempt (until the retry window ends).
 */
public final class RetryBackoff {

  private static final List<Duration> SCHEDULE =
      List.of(
          Duration.ofMinutes(5),
          Duration.ofMinutes(15),
          Duration.ofMinutes(30),
          Duration.ofHours(1),
          Duration.ofHours(2));

  private RetryBackoff() {}

  public static Duration next(int attempts) {
    int i = Math.clamp(attempts - 1L, 0, SCHEDULE.size() - 1);
    return SCHEDULE.get(i);
  }
}
