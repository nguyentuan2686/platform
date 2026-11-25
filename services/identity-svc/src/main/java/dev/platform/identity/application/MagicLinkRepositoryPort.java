package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MagicLinkRepositoryPort {

  void save(PendingMagicLink link);

  /** Creation times of links sent to the email after {@code since}. */
  List<Instant> requestTimesSince(Email email, Instant since);

  /** Atomically marks an unused, unexpired link as used and returns it; empty otherwise. */
  Optional<ConsumedMagicLink> consume(byte[] tokenSha256, Instant now);
}
