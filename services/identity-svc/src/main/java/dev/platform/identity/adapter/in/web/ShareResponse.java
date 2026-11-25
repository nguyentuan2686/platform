package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.ShareLink;
import java.time.Instant;

/** Body of {@code PUT/GET /me/profile/share}. */
public record ShareResponse(
    @JsonProperty("share_id") String shareId, @JsonProperty("created_at") Instant createdAt) {

  static ShareResponse of(ShareLink link) {
    return new ShareResponse(link.id().value(), link.createdAt());
  }
}
