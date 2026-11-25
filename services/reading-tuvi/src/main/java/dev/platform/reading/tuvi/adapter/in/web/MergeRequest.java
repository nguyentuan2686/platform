package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Body of {@code POST /internal/qa/merge}. Ids are parsed by hand so a bad value is reported on its
 * own field.
 */
public record MergeRequest(
    @JsonProperty("guest_id") String guestId, @JsonProperty("account_id") String accountId) {

  UUID guest() {
    return AskRequest.uuid(guestId, "guest_id");
  }

  UUID account() {
    return AskRequest.uuid(accountId, "account_id");
  }
}
