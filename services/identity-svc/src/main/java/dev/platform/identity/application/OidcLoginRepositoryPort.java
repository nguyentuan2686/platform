package dev.platform.identity.application;

import java.time.Instant;
import java.util.Optional;

public interface OidcLoginRepositoryPort {

  void save(PendingOidcLogin login);

  /** Marks the state used and returns it, only if it is unused and not expired. */
  Optional<PendingOidcLogin> consume(String state, Instant now);
}
