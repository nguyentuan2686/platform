package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.IssuedSession;
import java.time.Instant;
import java.util.UUID;

public record GuestSessionResponse(
    @JsonProperty("session_token") String sessionToken,
    @JsonProperty("guest_id") UUID guestId,
    @JsonProperty("expires_at") Instant expiresAt) {

  static GuestSessionResponse of(IssuedSession session) {
    return new GuestSessionResponse(
        session.token().value(), session.subject().id(), session.expiresAt());
  }

  @Override
  public String toString() {
    return "GuestSessionResponse[redacted]";
  }
}
