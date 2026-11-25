package dev.platform.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.random.RandomGenerator;

/** PKCE (RFC 7636, method S256) and the random values used for OIDC state and nonce. */
public final class Pkce {

  private static final int BYTES = 32;

  private Pkce() {}

  /** 32 random bytes as base64url without padding: 43 characters. */
  public static String randomValue(RandomGenerator random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** {@code BASE64URL(SHA256(ASCII(verifier)))}. */
  public static String challenge(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
