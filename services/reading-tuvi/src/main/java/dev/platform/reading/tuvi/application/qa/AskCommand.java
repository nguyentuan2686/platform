package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.util.UUID;

/** A new question. {@code toString} is redacted: it holds the bearer and the question. */
public record AskCommand(
    String bearer, UUID readingId, QaKind kind, QaTopic topic, String question, String lang) {

  @Override
  public String toString() {
    return "AskCommand[readingId=" + readingId + ", kind=" + kind + ", topic=" + topic + "]";
  }
}
