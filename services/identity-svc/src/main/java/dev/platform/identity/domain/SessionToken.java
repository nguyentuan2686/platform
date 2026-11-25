package dev.platform.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * An opaque bearer secret (32 random bytes, base64url without padding). Only its SHA-256 is ever
 * stored. Used for both session tokens and magic-link tokens. {@code toString} redacts.
 */
public record SessionToken(String value) {

  static final int BYTES = 32;
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

  public SessionToken {
    Objects.requireNonNull(value, "value");
  }

  public static SessionToken generate(RandomGenerator random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return new SessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  /** The token for a presented string, or empty when it cannot be one of ours. */
  public static Optional<SessionToken> parse(String presented) {
    if (presented == null || !FORMAT.matcher(presented).matches()) {
      return Optional.empty();
    }
    return Optional.of(new SessionToken(presented));
  }

  public byte[] sha256() {
    return sha256(value);
  }

  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return "SessionToken[redacted]";
  }
}
