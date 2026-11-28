package dev.platform.reading.tuvi.application.number;

/**
 * 409: the subject used up today's number checks ({@code number_quota}) or its IP did ({@code
 * number_quota_ip}).
 */
public class NumberQuotaExceededException extends RuntimeException {

  public static final String SUBJECT_REASON = "number_quota";
  public static final String IP_REASON = "number_quota_ip";

  private final transient NumberQuota quota;
  private final String reason;

  public NumberQuotaExceededException(NumberQuota quota) {
    this(quota, SUBJECT_REASON);
  }

  public NumberQuotaExceededException(NumberQuota quota, String reason) {
    super(reason);
    this.quota = quota;
    this.reason = reason;
  }

  public NumberQuota quota() {
    return quota;
  }

  public String reason() {
    return reason;
  }
}
