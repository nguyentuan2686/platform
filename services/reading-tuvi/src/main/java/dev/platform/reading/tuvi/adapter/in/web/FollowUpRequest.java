package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.FollowUpCommand;
import java.util.UUID;

/** Body of {@code POST /tuvi/qa/readings/{id}/follow-ups}. {@code toString} is redacted. */
public record FollowUpRequest(@JsonProperty("message_id") String messageId, String question) {

  FollowUpCommand toCommand(String bearer, UUID readingId) {
    return new FollowUpCommand(
        bearer, readingId, AskRequest.uuid(messageId, "message_id"), question);
  }

  @Override
  public String toString() {
    return "FollowUpRequest[redacted]";
  }
}
