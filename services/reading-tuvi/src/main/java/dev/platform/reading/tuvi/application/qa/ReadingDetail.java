package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reading with its decrypted messages. {@code notice} is the queued or refunded frame text, or
 * null. {@code toString} is redacted.
 */
public record ReadingDetail(
    UUID readingId,
    QaKind kind,
    QaTopic topic,
    QaStatus status,
    Instant createdAt,
    Instant retryUntil,
    int followUpsLeft,
    String notice,
    String disclaimer,
    List<Message> messages) {

  /** {@code answer} is null while unanswered. */
  public record Message(int seq, String question, String answer, Instant createdAt) {

    @Override
    public String toString() {
      return "Message[seq=" + seq + "]";
    }
  }

  @Override
  public String toString() {
    return "ReadingDetail[readingId=" + readingId + ", status=" + status + "]";
  }
}
