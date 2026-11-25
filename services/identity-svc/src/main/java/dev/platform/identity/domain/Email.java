package dev.platform.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** A normalized (trimmed, lower-cased) email address. Never printed: {@code toString} redacts. */
public record Email(String value) {

  static final int MAX_LENGTH = 254;

  // One plain address: dot-atom local part (max 64) and a hostname; ASCII only, so no commas,
  // quotes, brackets, whitespace or control characters can reach the mail library.
  private static final Pattern SHAPE =
      Pattern.compile(
          "(?=[^@]{1,64}@)[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
              + "@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

  public Email {
    if (value == null) {
      throw new IllegalArgumentException("email is required");
    }
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.length() > MAX_LENGTH || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("not a valid email address");
    }
  }

  /** Display form that hides most of the address, e.g. {@code t***@e***.com}. */
  public String masked() {
    int at = value.indexOf('@');
    String local = value.substring(0, at);
    String domain = value.substring(at + 1);
    int dot = domain.lastIndexOf('.');
    String suffix = dot > 0 ? domain.substring(dot) : "";
    return local.charAt(0) + "***@" + domain.charAt(0) + "***" + suffix;
  }

  @Override
  public String toString() {
    return "Email[redacted]";
  }
}
