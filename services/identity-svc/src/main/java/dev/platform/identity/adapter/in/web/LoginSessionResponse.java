package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.LoginResult;
import java.time.Instant;
import java.util.UUID;

public record LoginSessionResponse(
    @JsonProperty("session_token") String sessionToken,
    @JsonProperty("account_id") UUID accountId,
    @JsonProperty("expires_at") Instant expiresAt,
    boolean created) {

  static LoginSessionResponse of(LoginResult result) {
    return new LoginSessionResponse(
        result.session().token().value(),
        result.session().subject().id(),
        result.session().expiresAt(),
        result.created());
  }

  @Override
  public String toString() {
    return "LoginSessionResponse[redacted]";
  }
}
