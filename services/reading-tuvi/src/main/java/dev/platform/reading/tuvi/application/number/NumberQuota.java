package dev.platform.reading.tuvi.application.number;

/** Distinct numbers checked today (Vietnam day) by the caller, out of {@code limit}. */
public record NumberQuota(int limit, int used, int remaining) {

  public static NumberQuota of(int limit, int used) {
    return new NumberQuota(limit, used, Math.max(0, limit - used));
  }
}
