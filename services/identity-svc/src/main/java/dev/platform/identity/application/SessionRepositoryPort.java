package dev.platform.identity.application;

import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepositoryPort {

  void createGuest(UUID id, Instant now);

  void save(StoredSession session);

  /** The subject of an unexpired, unrevoked session with this token hash. */
  Optional<Subject> findActive(byte[] tokenSha256, Instant now);

  void revoke(byte[] tokenSha256, Instant now);

  void revokeGuestSessions(UUID guestId, Instant now);

  /** Records that the guest was folded into the account (first merge wins). */
  void markGuestMerged(UUID guestId, UUID accountId);
}
