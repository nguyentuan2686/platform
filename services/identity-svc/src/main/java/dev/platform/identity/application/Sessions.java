package dev.platform.identity.application;

import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Issues and stores a new session for a subject. */
final class Sessions {

  private Sessions() {}

  static IssuedSession issue(
      SessionRepositoryPort repository,
      RandomGenerator random,
      Subject subject,
      Duration ttl,
      Instant now) {
    SessionToken token = SessionToken.generate(random);
    Instant expiresAt = now.plus(ttl);
    repository.save(new StoredSession(UUID.randomUUID(), token.sha256(), subject, now, expiresAt));
    return new IssuedSession(token, subject, expiresAt);
  }
}
