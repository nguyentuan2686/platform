package dev.platform.identity.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Unguessable public id of a profile share link: 16 random bytes, base64url, 22 characters. */
public record ShareId(String value) {

  private static final int BYTES = 16;
  private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{22}$");

  public ShareId {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("not a share id");
    }
  }

  public static ShareId random(SecureRandom random) {
    byte[] bytes = new byte[BYTES];
    random.nextBytes(bytes);
    return new ShareId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }

  public static ShareId parse(String raw) {
    return new ShareId(raw);
  }
}
