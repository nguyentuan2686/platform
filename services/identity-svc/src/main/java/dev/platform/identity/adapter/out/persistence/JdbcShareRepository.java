package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.ShareLink;
import dev.platform.identity.application.ShareRepositoryPort;
import dev.platform.identity.application.StoredProfile;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.ShareId;
import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcShareRepository implements ShareRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcShareRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static String column(Subject owner) {
    return owner instanceof Subject.Guest ? "guest_id" : "account_id";
  }

  @Override
  public Optional<ShareLink> findActive(Subject owner) {
    return jdbc.sql(
            "select s.id, s.created_at from profile_share s"
                + " join chart_profile p on p.id = s.profile_id"
                + " where p."
                + column(owner)
                + " = :owner and s.revoked_at is null")
        .param("owner", owner.id())
        .query(
            (rs, i) ->
                new ShareLink(ShareId.parse(rs.getString("id")), Timestamps.read(rs, "created_at")))
        .optional();
  }

  @Override
  public boolean insertActive(ShareId id, Subject owner, Instant now) {
    return jdbc.sql(
                "insert into profile_share (id, profile_id, created_at)"
                    + " select :id, p.id, :now from chart_profile p where p."
                    + column(owner)
                    + " = :owner on conflict do nothing")
            .param("id", id.value())
            .param("owner", owner.id())
            .param("now", Timestamps.toDb(now))
            .update()
        == 1;
  }

  @Override
  public void revokeActive(Subject owner, Instant now) {
    jdbc.sql(
            "update profile_share set revoked_at = :now where revoked_at is null"
                + " and profile_id in (select id from chart_profile where "
                + column(owner)
                + " = :owner)")
        .param("owner", owner.id())
        .param("now", Timestamps.toDb(now))
        .update();
  }

  @Override
  public Optional<StoredProfile> findProfileByActiveShare(ShareId id) {
    return jdbc.sql(
            "select p.id, p.label, p.birth_enc, p.key_id, p.calendar from profile_share s"
                + " join chart_profile p on p.id = s.profile_id"
                + " where s.id = :id and s.revoked_at is null")
        .param("id", id.value())
        .query(
            (rs, i) ->
                new StoredProfile(
                    rs.getObject("id", UUID.class),
                    rs.getString("label"),
                    rs.getBytes("birth_enc"),
                    rs.getInt("key_id"),
                    Calendar.valueOf(rs.getString("calendar"))))
        .optional();
  }
}
