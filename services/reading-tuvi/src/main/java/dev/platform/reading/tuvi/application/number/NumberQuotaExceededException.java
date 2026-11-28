package dev.platform.reading.tuvi.application.number;

/** 409 {@code number_quota}: the subject (or its IP) used up today's number checks. */
public class NumberQuotaExceededException extends RuntimeException {

  private final transient NumberQuota quota;

  public NumberQuotaExceededException(NumberQuota quota) {
    super("number_quota");
    this.quota = quota;
  }

  public NumberQuota quota() {
    return quota;
  }
}
