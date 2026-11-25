package dev.platform.identity.application;

import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.ChartProfile;
import dev.platform.identity.domain.Subject;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/** The one chart profile a guest or account owns, and its move onto an account at login. */
public class ProfileUseCase implements AccountMergePort {

  private final ProfileRepositoryPort profiles;
  private final SessionRepositoryPort sessions;
  private final BirthCipherPort cipher;
  private final Clock clock;
  private final TransactionOperations tx;

  public ProfileUseCase(
      ProfileRepositoryPort profiles,
      SessionRepositoryPort sessions,
      BirthCipherPort cipher,
      Clock clock,
      TransactionOperations tx) {
    this.profiles = profiles;
    this.sessions = sessions;
    this.cipher = cipher;
    this.clock = clock;
    this.tx = tx;
  }

  public Optional<ProfileView> get(Subject owner) {
    return tx.execute(
        status -> {
          boolean locked = locked(owner);
          return profiles.find(owner).map(stored -> new ProfileView(open(stored), locked));
        });
  }

  /** Creates the profile, or updates it; a locked birth cannot change. */
  public ProfileView put(Subject owner, String label, BirthData birth, Calendar calendar) {
    return tx.execute(
        status -> {
          boolean locked = locked(owner);
          Optional<StoredProfile> existing = profiles.find(owner);
          if (existing.isEmpty()) {
            ChartProfile created = ChartProfile.create(UUID.randomUUID(), label, birth, calendar);
            if (profiles.insert(owner, seal(created), clock.instant())) {
              return new ProfileView(created, locked);
            }
            existing = profiles.find(owner); // a concurrent request created it first
          }
          ChartProfile updated =
              open(existing.orElseThrow()).update(label, birth, calendar, locked);
          profiles.update(seal(updated), clock.instant());
          return new ProfileView(updated, locked);
        });
  }

  /** The account's birth lock is not touched: it outlives the profile. */
  public void delete(Subject owner) {
    tx.executeWithoutResult(status -> profiles.delete(owner));
  }

  /** The guest profile moves to the account if it has none; otherwise it is dropped. */
  @Override
  public void mergeGuest(UUID guestId, UUID accountId) {
    tx.executeWithoutResult(
        status -> {
          profiles.lockAccount(accountId);
          if (profiles.find(new Subject.Account(accountId)).isPresent()) {
            profiles.delete(new Subject.Guest(guestId));
          } else {
            profiles.moveGuestProfile(guestId, accountId);
          }
          sessions.markGuestMerged(guestId, accountId);
        });
  }

  private boolean locked(Subject owner) {
    return owner instanceof Subject.Account a && profiles.birthLocked(a.id());
  }

  private StoredProfile seal(ChartProfile profile) {
    return new StoredProfile(
        profile.id(),
        profile.label(),
        cipher.encrypt(profile.id(), profile.birth()),
        cipher.currentKeyId(),
        profile.calendar());
  }

  private ChartProfile open(StoredProfile stored) {
    return new ChartProfile(
        stored.id(),
        stored.label(),
        cipher.decrypt(stored.id(), stored.birthEnc()),
        stored.calendar());
  }
}
