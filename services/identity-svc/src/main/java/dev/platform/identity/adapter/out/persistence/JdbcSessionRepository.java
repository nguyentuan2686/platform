package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.SessionRepositoryPort;
import dev.platform.identity.application.StoredSession;
import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcSessionRepository implements SessionRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcSessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void createGuest(UUID id, Instant now) {
    jdbc.sql("insert into guest (id, created_at) values (:id, :now)")
        .param("id", id)
        .param("now", Timestamps.toDb(now))
        .update();
  }

  @Override
  public void save(StoredSession session) {
    UUID guestId = session.subject() instanceof Subject.Guest g ? g.id() : null;
    UUID accountId = session.subject() instanceof Subject.Account a ? a.id() : null;
    jdbc.sql(
            "insert into session (id, token_sha256, guest_id, account_id, created_at, expires_at)"
                + " values (:id, :hash, :guest, :account, :created, :expires)")
        .param("id", session.id())
        .param("hash", session.tokenSha256())
        .param("guest", guestId, java.sql.Types.OTHER)
        .param("account", accountId, java.sql.Types.OTHER)
        .param("created", Timestamps.toDb(session.createdAt()))
        .param("expires", Timestamps.toDb(session.expiresAt()))
        .update();
  }

  @Override
  public Optional<Subject> findActive(byte[] tokenSha256, Instant now) {
    return jdbc.sql(
            "select guest_id, account_id from session"
                + " where token_sha256 = :hash and revoked_at is null and expires_at > :now")
        .param("hash", tokenSha256)
        .param("now", Timestamps.toDb(now))
        .query(
            (rs, i) -> {
              UUID guest = rs.getObject("guest_id", UUID.class);
              return guest != null
                  ? (Subject) new Subject.Guest(guest)
                  : new Subject.Account(rs.getObject("account_id", UUID.class));
            })
        .optional();
  }

  @Override
  public void revoke(byte[] tokenSha256, Instant now) {
    jdbc.sql(
            "update session set revoked_at = :now"
                + " where token_sha256 = :hash and revoked_at is null")
        .param("hash", tokenSha256)
        .param("now", Timestamps.toDb(now))
        .update();
  }

  @Override
  public void markGuestMerged(UUID guestId, UUID accountId) {
    jdbc.sql("update guest set merged_into = :account where id = :guest and merged_into is null")
        .param("guest", guestId)
        .param("account", accountId)
        .update();
  }

  @Override
  public void revokeGuestSessions(UUID guestId, Instant now) {
    jdbc.sql("update session set revoked_at = :now where guest_id = :guest and revoked_at is null")
        .param("guest", guestId)
        .param("now", Timestamps.toDb(now))
        .update();
  }
}
