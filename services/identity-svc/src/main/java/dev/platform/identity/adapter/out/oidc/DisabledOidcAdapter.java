package dev.platform.identity.adapter.out.oidc;

import dev.platform.identity.application.IdTokenClaims;
import dev.platform.identity.application.OidcPort;
import dev.platform.identity.application.OidcUnavailableException;
import java.net.URI;

/** Stands in when no Google client id is configured. */
public class DisabledOidcAdapter implements OidcPort {

  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public URI authorizationUri(String state, String nonce, String codeChallenge, URI redirectUri) {
    throw OidcUnavailableException.notConfigured();
  }

  @Override
  public IdTokenClaims exchange(String code, String codeVerifier, URI redirectUri) {
    throw OidcUnavailableException.notConfigured();
  }
}
