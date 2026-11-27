package dev.platform.identity.application;

import dev.platform.identity.domain.ClientKey;
import dev.platform.identity.domain.Email;
import java.time.Instant;
import java.util.UUID;

/**
 * A magic link as persisted: only the SHA-256 of the token. {@code guestId} and {@code client} (who
 * asked, for the per-client cap) may be null.
 */
public record PendingMagicLink(
    UUID id,
    Email email,
    byte[] tokenSha256,
    UUID guestId,
    ClientKey client,
    Instant createdAt,
    Instant expiresAt) {

  @Override
  public String toString() {
    return "PendingMagicLink[redacted]";
  }
}
