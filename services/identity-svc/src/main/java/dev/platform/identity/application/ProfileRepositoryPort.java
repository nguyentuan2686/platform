package dev.platform.identity.application;

import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepositoryPort {

  Optional<StoredProfile> find(Subject owner);

  /** False when the owner already has a profile. */
  boolean insert(Subject owner, StoredProfile profile, Instant now);

  void update(StoredProfile profile, Instant now);

  /** True when a profile was deleted. */
  boolean delete(Subject owner);

  /** Whether the account's birth data is locked; holds a shared lock on the account row. */
  boolean birthLocked(UUID accountId);

  /** Exclusive lock on the account row, so a merge cannot race a profile write. */
  void lockAccount(UUID accountId);

  /** Hands the guest's profile to the account; false when the guest has none. */
  boolean moveGuestProfile(UUID guestId, UUID accountId);
}
