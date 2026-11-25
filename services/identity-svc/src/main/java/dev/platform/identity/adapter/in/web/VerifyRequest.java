package dev.platform.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/magic-links/verify}. The token is a secret, so it is redacted. */
public record VerifyRequest(@NotBlank(message = "token is required") String token) {

  @Override
  public String toString() {
    return "VerifyRequest[redacted]";
  }
}
