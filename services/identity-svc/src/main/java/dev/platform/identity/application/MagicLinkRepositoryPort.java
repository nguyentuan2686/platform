package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MagicLinkRepositoryPort {

  /** Serializes concurrent requests for one email until the surrounding transaction ends. */
  void lock(Email email);

  void save(PendingMagicLink link);

  /** Creation times of links sent to the email after {@code since}. */
  List<Instant> requestTimesSince(Email email, Instant since);

  /** Number of links sent to any address after {@code since} (the global daily cap). */
  long countSince(Instant since);

  /** Atomically marks an unused, unexpired link as used and returns it; empty otherwise. */
  Optional<ConsumedMagicLink> consume(byte[] tokenSha256, Instant now);
}
