package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;

/** Extracts the session token from an {@code Authorization: Bearer <token>} header. */
final class Bearer {

  private static final String PREFIX = "Bearer ";

  private Bearer() {}

  static String require(String header) {
    if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      throw new UnauthenticatedException();
    }
    String token = header.substring(PREFIX.length()).strip();
    // only visible ASCII can be forwarded as a header value; anything else is not a session
    if (token.isEmpty() || !token.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
      throw new UnauthenticatedException();
    }
    return token;
  }
}
