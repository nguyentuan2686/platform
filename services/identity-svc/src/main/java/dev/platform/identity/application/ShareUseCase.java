package dev.platform.identity.application;

import dev.platform.identity.domain.ShareId;
import dev.platform.identity.domain.Subject;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/** The public share link of the owner's chart profile: create, read, revoke, resolve. */
public class ShareUseCase {

  private final ShareRepositoryPort shares;
  private final BirthCipherPort cipher;
  private final Clock clock;
  private final SecureRandom random;
  private final TransactionOperations tx;

  public ShareUseCase(
      ShareRepositoryPort shares,
      BirthCipherPort cipher,
      Clock clock,
      SecureRandom random,
      TransactionOperations tx) {
    this.shares = shares;
    this.cipher = cipher;
    this.clock = clock;
    this.random = random;
    this.tx = tx;
  }

  /** Idempotent: returns the active link, else issues one. */
  public ShareLink create(Subject owner) {
    return tx.execute(
        status -> {
          Optional<ShareLink> active = shares.findActive(owner);
          if (active.isPresent()) {
            return active.get();
          }
          shares.insertActive(ShareId.random(random), owner, clock.instant());
          // Empty when there is no profile; present when a concurrent request issued it first.
          return shares.findActive(owner).orElseThrow(NoProfileException::new);
        });
  }

  public Optional<ShareLink> get(Subject owner) {
    return shares.findActive(owner);
  }

  public void revoke(Subject owner) {
    shares.revokeActive(owner, clock.instant());
  }

  /** Empty for a malformed, unknown or revoked id, or a deleted profile. */
  public Optional<SharedProfile> resolve(String rawShareId) {
    ShareId id;
    try {
      id = ShareId.parse(rawShareId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return shares
        .findProfileByActiveShare(id)
        .map(p -> new SharedProfile(cipher.decrypt(p.id(), p.birthEnc()), p.calendar()));
  }
}
