package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeResponse(
    String kind,
    @JsonProperty("guest_id") UUID guestId,
    @JsonProperty("account_id") UUID accountId,
    @JsonProperty("email_masked") String emailMasked) {

  static MeResponse guest(UUID guestId) {
    return new MeResponse("guest", guestId, null, null);
  }

  static MeResponse account(UUID accountId, String emailMasked) {
    return new MeResponse("account", null, accountId, emailMasked);
  }
}
