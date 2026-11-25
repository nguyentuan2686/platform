package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.LedgerPort;
import dev.platform.identity.application.ProcessedEventPort;
import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.Entitlements;
import dev.platform.identity.domain.PassKind;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ledger, passes and processed events. Runs inside the use case's transaction. */
public class JdbcLedgerRepository implements LedgerPort, ProcessedEventPort {

  private static final String ENTRY_COLUMNS =
      "account_id, delta, source, pass_id, reason, ref, topic, created_at";

  private final JdbcClient jdbc;

  public JdbcLedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean lockAccount(UUID accountId) {
    return jdbc.sql("select 1 from account where id = :id for update")
        .param("id", accountId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  @Override
  public Entitlements load(UUID accountId, Instant todayStart) {
    int credits =
        jdbc.sql(
                "select coalesce(sum(delta), 0) from credit_ledger"
                    + " where account_id = :id and source = 'CREDIT'")
            .param("id", accountId)
            .query(Integer.class)
            .single();
    List<Entitlements.PassState> passes =
        jdbc.sql(
                "select p.id, p.kind, p.starts_at, p.ends_at, p.questions_included,"
                    + " coalesce((select -sum(l.delta) from credit_ledger l"
                    + "           where l.pass_id = p.id), 0) as used"
                    + " from pass p where p.account_id = :id order by p.ends_at")
            .param("id", accountId)
            .query(
                (rs, i) ->
                    new Entitlements.PassState(
                        rs.getObject("id", UUID.class),
                        PassKind.valueOf(rs.getString("kind")),
                        Timestamps.read(rs, "starts_at"),
                        Timestamps.read(rs, "ends_at"),
                        (Integer) rs.getObject("questions_included"),
                        rs.getInt("used")))
            .list();
    List<Entitlements.PassUse> uses =
        jdbc.sql(
                "select pass_id, delta, created_at from credit_ledger"
                    + " where account_id = :id and source = 'PASS' and created_at >= :since")
            .param("id", accountId)
            .param("since", Timestamps.toDb(todayStart))
            .query(
                (rs, i) ->
                    new Entitlements.PassUse(
                        rs.getObject("pass_id", UUID.class),
                        rs.getInt("delta"),
                        Timestamps.read(rs, "created_at")))
            .list();
    return new Entitlements(credits, passes, uses);
  }

  @Override
  public Optional<Entry> find(String reason, String ref) {
    return jdbc.sql(
            "select " + ENTRY_COLUMNS + " from credit_ledger where reason = :reason and ref = :ref")
        .param("reason", reason)
        .param("ref", ref)
        .query((rs, i) -> entry(rs))
        .optional();
  }

  @Override
  public boolean append(NewEntry e) {
    return jdbc.sql(
                "insert into credit_ledger (account_id, delta, source, pass_id, reason, ref,"
                    + " topic, created_at)"
                    + " values (:account, :delta, :source, :pass, :reason, :ref, :topic, :at)"
                    + " on conflict (reason, ref) do nothing")
            .param("account", e.accountId())
            .param("delta", e.delta())
            .param("source", e.source().name())
            .param("pass", e.passId(), Types.OTHER)
            .param("reason", e.reason())
            .param("ref", e.ref())
            .param("topic", e.topic(), Types.VARCHAR)
            .param("at", Timestamps.toDb(e.at()))
            .update()
        == 1;
  }

  @Override
  public Optional<Instant> latestPassEnd(UUID accountId) {
    return jdbc.sql("select max(ends_at) from pass where account_id = :id")
        .param("id", accountId)
        .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class))
        .optional()
        .map(java.time.OffsetDateTime::toInstant);
  }

  @Override
  public boolean insertPass(
      UUID id,
      UUID accountId,
      PassKind kind,
      UUID orderId,
      Instant startsAt,
      Instant endsAt,
      Integer questions) {
    return jdbc.sql(
                "insert into pass (id, account_id, kind, order_id, starts_at, ends_at,"
                    + " questions_included)"
                    + " values (:id, :account, :kind, :order, :starts, :ends, :questions)"
                    + " on conflict (order_id) do nothing")
            .param("id", id)
            .param("account", accountId)
            .param("kind", kind.name())
            .param("order", orderId)
            .param("starts", Timestamps.toDb(startsAt))
            .param("ends", Timestamps.toDb(endsAt))
            .param("questions", questions, Types.INTEGER)
            .update()
        == 1;
  }

  @Override
  public List<Entry> recent(UUID accountId, int limit) {
    return jdbc.sql(
            "select "
                + ENTRY_COLUMNS
                + " from credit_ledger where account_id = :id order by id desc limit :limit")
        .param("id", accountId)
        .param("limit", limit)
        .query((rs, i) -> entry(rs))
        .list();
  }

  @Override
  public void lockBirth(UUID accountId, Instant now) {
    jdbc.sql(
            "update account set birth_locked_at = :now"
                + " where id = :id and birth_locked_at is null")
        .param("id", accountId)
        .param("now", Timestamps.toDb(now))
        .update();
  }

  @Override
  public boolean markProcessed(UUID eventId) {
    return jdbc.sql("insert into processed_event (event_id) values (:id) on conflict do nothing")
            .param("id", eventId)
            .update()
        == 1;
  }

  private static Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Entry(
        rs.getObject("account_id", UUID.class),
        rs.getInt("delta"),
        CreditSource.valueOf(rs.getString("source")),
        rs.getObject("pass_id", UUID.class),
        rs.getString("reason"),
        rs.getString("ref"),
        rs.getString("topic"),
        Timestamps.read(rs, "created_at"));
  }
}
