package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.ConsumedMagicLink;
import dev.platform.identity.application.MagicLinkRepositoryPort;
import dev.platform.identity.application.PendingMagicLink;
import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcMagicLinkRepository implements MagicLinkRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcMagicLinkRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(PendingMagicLink link) {
    jdbc.sql(
            "insert into magic_link (id, email, token_sha256, guest_id, created_at, expires_at)"
                + " values (:id, :email, :hash, :guest, :created, :expires)")
        .param("id", link.id())
        .param("email", link.email().value())
        .param("hash", link.tokenSha256())
        .param("guest", link.guestId(), java.sql.Types.OTHER)
        .param("created", Timestamps.toDb(link.createdAt()))
        .param("expires", Timestamps.toDb(link.expiresAt()))
        .update();
  }

  @Override
  public List<Instant> requestTimesSince(Email email, Instant since) {
    return jdbc.sql(
            "select created_at from magic_link where email = :email and created_at > :since")
        .param("email", email.value())
        .param("since", Timestamps.toDb(since))
        .query((rs, i) -> Timestamps.read(rs, "created_at"))
        .list();
  }

  @Override
  public Optional<ConsumedMagicLink> consume(byte[] tokenSha256, Instant now) {
    return jdbc.sql(
            "update magic_link set used_at = :now"
                + " where token_sha256 = :hash and used_at is null and expires_at > :now"
                + " returning email, guest_id")
        .param("hash", tokenSha256)
        .param("now", Timestamps.toDb(now))
        .query(
            (rs, i) ->
                new ConsumedMagicLink(
                    new Email(rs.getString("email")), rs.getObject("guest_id", UUID.class)))
        .optional();
  }
}
