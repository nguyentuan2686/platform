package dev.platform.reading.tuvi.application.number;

import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import java.time.LocalDate;

/** Daily number-check usage, stored as HMACs only ({@code number_check_usage}). */
public interface NumberUsagePort {

  /**
   * In one transaction under a per-subject lock: a check hash already used today by the subject is
   * free; otherwise the subject must have fewer than {@code perSubject} distinct hashes today and
   * the IP (when given) fewer than {@code perIp}, and the row is recorded.
   *
   * @return the subject's quota after this check
   * @throws NumberQuotaExceededException when a limit is reached
   */
  NumberQuota recordOrReuse(
      IdentitySubject subject,
      LocalDate vnDay,
      byte[] checkHash,
      byte[] ipHash,
      int perSubject,
      int perIp);

  NumberQuota quota(IdentitySubject subject, LocalDate vnDay, int perSubject);

  /** Deletes every row with {@code vn_day < day}; returns how many. */
  int purgeBefore(LocalDate day);
}
