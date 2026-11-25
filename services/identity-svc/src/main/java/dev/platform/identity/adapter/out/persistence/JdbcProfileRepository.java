package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.ProfileRepositoryPort;
import dev.platform.identity.application.StoredProfile;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.Subject;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcProfileRepository implements ProfileRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcProfileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static String column(Subject owner) {
    return owner instanceof Subject.Guest ? "guest_id" : "account_id";
  }

  @Override
  public Optional<StoredProfile> find(Subject owner) {
    return jdbc.sql(
            "select id, label, birth_enc, key_id, calendar from chart_profile where "
                + column(owner)
                + " = :owner")
        .param("owner", owner.id())
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

  @Override
  public boolean insert(Subject owner, StoredProfile profile, Instant now) {
    UUID guest = owner instanceof Subject.Guest g ? g.id() : null;
    UUID account = owner instanceof Subject.Account a ? a.id() : null;
    return jdbc.sql(
                "insert into chart_profile (id, guest_id, account_id, label, birth_enc, key_id,"
                    + " calendar, created_at, updated_at)"
                    + " values (:id, :guest, :account, :label, :enc, :key, :calendar, :now, :now)"
                    + " on conflict do nothing")
            .param("id", profile.id())
            .param("guest", guest, Types.OTHER)
            .param("account", account, Types.OTHER)
            .param("label", profile.label())
            .param("enc", profile.birthEnc())
            .param("key", profile.keyId())
            .param("calendar", profile.calendar().name())
            .param("now", Timestamps.toDb(now))
            .update()
        == 1;
  }

  @Override
  public void update(StoredProfile profile, Instant now) {
    jdbc.sql(
            "update chart_profile set label = :label, birth_enc = :enc, key_id = :key,"
                + " calendar = :calendar, updated_at = :now where id = :id")
        .param("id", profile.id())
        .param("label", profile.label())
        .param("enc", profile.birthEnc())
        .param("key", profile.keyId())
        .param("calendar", profile.calendar().name())
        .param("now", Timestamps.toDb(now))
        .update();
  }

  @Override
  public boolean delete(Subject owner) {
    return jdbc.sql("delete from chart_profile where " + column(owner) + " = :owner")
            .param("owner", owner.id())
            .update()
        > 0;
  }

  @Override
  public boolean birthLocked(UUID accountId) {
    return jdbc.sql("select birth_locked_at is not null from account where id = :id for share")
        .param("id", accountId)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  @Override
  public void lockAccount(UUID accountId) {
    jdbc.sql("select 1 from account where id = :id for update")
        .param("id", accountId)
        .query(Integer.class)
        .optional();
  }

  @Override
  public boolean moveGuestProfile(UUID guestId, UUID accountId) {
    return jdbc.sql(
                "update chart_profile set account_id = :account, guest_id = null"
                    + " where guest_id = :guest")
            .param("guest", guestId)
            .param("account", accountId)
            .update()
        == 1;
  }
}
