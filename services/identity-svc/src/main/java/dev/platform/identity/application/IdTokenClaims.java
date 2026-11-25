package dev.platform.identity.application;

import dev.platform.identity.domain.Email;

/** The verified claims of a Google ID token that login needs. Never printed. */
public record IdTokenClaims(String subject, Email email, boolean emailVerified, String nonce) {

  @Override
  public String toString() {
    return "IdTokenClaims[redacted]";
  }
}
