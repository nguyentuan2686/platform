package dev.platform.reading.tuvi.application.qa;

import java.util.UUID;

/** A follow-up on an answered paid reading. {@code toString} is redacted. */
public record FollowUpCommand(String bearer, UUID readingId, UUID messageId, String question) {

  @Override
  public String toString() {
    return "FollowUpCommand[readingId=" + readingId + ", messageId=" + messageId + "]";
  }
}
