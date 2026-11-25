package dev.platform.identity.domain;

import java.util.Locale;

/** A normalized (trimmed, lower-cased) email address. Never printed: {@code toString} redacts. */
public record Email(String value) {

  static final int MAX_LENGTH = 254;

  public Email {
    if (value == null) {
      throw new IllegalArgumentException("email is required");
    }
    value = value.strip().toLowerCase(Locale.ROOT);
    int at = value.indexOf('@');
    if (value.isEmpty()
        || value.length() > MAX_LENGTH
        || at <= 0
        || at != value.lastIndexOf('@')
        || at == value.length() - 1
        || value.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("not a valid email address");
    }
  }

  /** Display form that hides most of the address, e.g. {@code t***@g***.com}. */
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
