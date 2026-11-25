package dev.platform.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /auth/google/callback}. Code and state are secrets, so they are redacted. */
public record GoogleCallbackRequest(
    @NotBlank(message = "code is required") @Size(max = 2048, message = "code is too long")
        String code,
    @NotBlank(message = "state is required") @Size(max = 128, message = "state is too long")
        String state) {

  @Override
  public String toString() {
    return "GoogleCallbackRequest[redacted]";
  }
}
