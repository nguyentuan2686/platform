package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.UnauthenticatedException;
import java.util.Optional;

/** Extracts the token from an {@code Authorization: Bearer <token>} header. */
final class Bearer {

  private static final String PREFIX = "Bearer ";

  private Bearer() {}

  static Optional<String> parse(String header) {
    if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      return Optional.empty();
    }
    String token = header.substring(PREFIX.length()).strip();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  static String require(String header) {
    return parse(header).orElseThrow(UnauthenticatedException::new);
  }
}
