package dev.platform.identity.adapter.out.persistence;

import dev.platform.identity.application.AccountRepositoryPort;
import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcAccountRepository implements AccountRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Upserted upsert(Email email, Instant now) {
    Optional<UUID> inserted =
        jdbc.sql(
                "insert into account (id, email, created_at) values (:id, :email, :now)"
                    + " on conflict (email) do nothing returning id")
            .param("id", UUID.randomUUID())
            .param("email", email.value())
            .param("now", Timestamps.toDb(now))
            .query(UUID.class)
            .optional();
    if (inserted.isPresent()) {
      return new Upserted(inserted.get(), true);
    }
    UUID existing =
        jdbc.sql("select id from account where email = :email")
            .param("email", email.value())
            .query(UUID.class)
            .single();
    return new Upserted(existing, false);
  }

  @Override
  public Optional<Email> findEmail(UUID accountId) {
    return jdbc.sql("select email from account where id = :id")
        .param("id", accountId)
        .query(String.class)
        .optional()
        .map(Email::new);
  }

  @Override
  public Optional<UUID> findByGoogleSub(String sub) {
    return jdbc.sql("select id from account where google_sub = :sub")
        .param("sub", sub)
        .query(UUID.class)
        .optional();
  }

  @Override
  public boolean linkGoogleSub(UUID accountId, String sub) {
    return jdbc.sql(
                "update account set google_sub = :sub"
                    + " where id = :id and google_sub is null"
                    + " and not exists (select 1 from account where google_sub = :sub)")
            .param("sub", sub)
            .param("id", accountId)
            .update()
        == 1;
  }
}
