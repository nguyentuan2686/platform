package dev.platform.identity.application;

import dev.platform.identity.domain.Subject;
import java.time.Instant;
import java.util.UUID;

/** A session as persisted: only the SHA-256 of the token, never the token. */
public record StoredSession(
    UUID id, byte[] tokenSha256, Subject subject, Instant createdAt, Instant expiresAt) {

  @Override
  public String toString() {
    return "StoredSession[redacted]";
  }
}
