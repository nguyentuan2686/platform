package dev.platform.reading.tuvi.adapter.out.persistence;

import dev.platform.reading.tuvi.application.number.NumberQuota;
import dev.platform.reading.tuvi.application.number.NumberQuotaExceededException;
import dev.platform.reading.tuvi.application.number.NumberUsagePort;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code number_check_usage}: one row per (subject, VN day, check hash). A check runs in one
 * transaction holding {@code pg_advisory_xact_lock} on the subject, so two concurrent new numbers
 * with one check left cannot both pass.
 */
@Repository
public class JdbcNumberUsage implements NumberUsagePort {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public JdbcNumberUsage(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txManager);
  }

  @Override
  public NumberQuota recordOrReuse(
      IdentitySubject s, LocalDate day, byte[] check, byte[] ip, int perSubject, int perIp) {
    return tx.execute(
        status -> {
          jdbc.query(
              "select pg_advisory_xact_lock(hashtext(?))",
              (RowCallbackHandler) rs -> {},
              "number-check:" + s.kind() + ":" + s.id());
          if (ip != null) {
            // always after the subject lock (fixed order, no deadlock): serializes the IP count
            jdbc.query(
                "select pg_advisory_xact_lock(hashtext('number-check-ip:' || encode(?, 'hex')))",
                (RowCallbackHandler) rs -> {},
                (Object) ip);
          }
          int used = used(s, day);
          Integer seen =
              jdbc.queryForObject(
                  "select count(*) from number_check_usage where subject_kind = ?"
                      + " and subject_id = ? and vn_day = ? and check_hash = ?",
                  Integer.class,
                  s.kind(),
                  s.id(),
                  day,
                  check);
          if (seen != null && seen > 0) {
            return NumberQuota.of(perSubject, used);
          }
          if (used >= perSubject) {
            throw new NumberQuotaExceededException(NumberQuota.of(perSubject, used));
          }
          if (ip != null) {
            Integer byIp =
                jdbc.queryForObject(
                    "select count(distinct check_hash) from number_check_usage"
                        + " where vn_day = ? and ip_hash = ?",
                    Integer.class,
                    day,
                    ip);
            if (byIp != null && byIp >= perIp) {
              throw new NumberQuotaExceededException(
                  new NumberQuota(perSubject, used, 0), NumberQuotaExceededException.IP_REASON);
            }
          }
          jdbc.update(
              "insert into number_check_usage (subject_kind, subject_id, vn_day, check_hash,"
                  + " ip_hash) values (?, ?, ?, ?, ?)",
              s.kind(),
              s.id(),
              day,
              check,
              ip);
          return NumberQuota.of(perSubject, used + 1);
        });
  }

  @Override
  public NumberQuota quota(IdentitySubject s, LocalDate day, int perSubject) {
    return NumberQuota.of(perSubject, used(s, day));
  }

  @Override
  public int purgeBefore(LocalDate day) {
    return jdbc.update("delete from number_check_usage where vn_day < ?", day);
  }

  private int used(IdentitySubject s, LocalDate day) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from number_check_usage where subject_kind = ? and subject_id = ?"
                + " and vn_day = ?",
            Integer.class,
            s.kind(),
            s.id(),
            day);
    return n == null ? 0 : n;
  }
}
