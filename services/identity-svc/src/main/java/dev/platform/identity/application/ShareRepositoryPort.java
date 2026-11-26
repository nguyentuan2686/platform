package dev.platform.identity.application;

import dev.platform.identity.domain.ShareId;
import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.Optional;

public interface ShareRepositoryPort {

  /** The active (not revoked) share of the owner. */
  Optional<ShareLink> findActive(Subject owner);

  /** False when the owner has no profile or already has an active share. */
  boolean insertActive(ShareId id, Subject owner, Instant now);

  /** Revokes the active share of the owner; a no-op when there is none. */
  void revokeActive(Subject owner, Instant now);

  /** The profile behind an active share. */
  Optional<StoredProfile> findProfileByActiveShare(ShareId id);
}
