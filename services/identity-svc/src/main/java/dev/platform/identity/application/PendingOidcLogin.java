package dev.platform.identity.application;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** An authorization in flight: the secrets bound to one {@code state}. Never printed. */
public record PendingOidcLogin(
    String state,
    String nonce,
    String codeVerifier,
    URI redirectUri,
    UUID guestId,
    Instant expiresAt) {

  @Override
  public String toString() {
    return "PendingOidcLogin[redacted]";
  }
}
