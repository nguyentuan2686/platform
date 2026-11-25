package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepositoryPort {

  /** Finds the account for the email or creates it; {@code created} tells which. */
  Upserted upsert(Email email, Instant now);

  Optional<Email> findEmail(UUID accountId);

  record Upserted(UUID id, boolean created) {}
}
