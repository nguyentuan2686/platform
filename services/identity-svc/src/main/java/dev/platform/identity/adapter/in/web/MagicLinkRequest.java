package dev.platform.identity.adapter.in.web;

import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /auth/magic-links}. Holds an email, so {@code toString} redacts. */
public record MagicLinkRequest(
    @ValidEmail String email,
    @Pattern(regexp = "[a-z]{2}", message = "must be a two-letter language code") String lang) {

  /** The language, defaulting to Vietnamese. */
  String langOrDefault() {
    return lang == null ? "vi" : lang;
  }

  @Override
  public String toString() {
    return "MagicLinkRequest[redacted]";
  }
}
