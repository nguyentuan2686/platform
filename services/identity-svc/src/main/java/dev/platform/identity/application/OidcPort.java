package dev.platform.identity.application;

import java.net.URI;

/** The OpenID Connect provider (Google): authorization URL and code exchange. */
public interface OidcPort {

  /** False when the provider is not configured; login then answers 503. */
  boolean enabled();

  URI authorizationUri(String state, String nonce, String codeChallenge, URI redirectUri);

  /**
   * Exchanges the code and returns the claims of the verified ID token (signature, issuer, audience
   * and expiry checked). Throws {@link UnauthenticatedException} when the provider or the token is
   * not acceptable, {@link OidcUnavailableException} when the provider cannot be reached.
   */
  IdTokenClaims exchange(String code, String codeVerifier, URI redirectUri);
}
