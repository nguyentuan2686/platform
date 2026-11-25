package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.Prompt;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of the synchronous part of a question: either the outcome is already known (replay,
 * refusal, busy) or a {@link Job} must run on the Q&A executor.
 */
public sealed interface Prepared {

  UUID readingId();

  QaKind kind();

  QaTopic topic();

  /** Follow-ups left once this message is answered (0 for free chats). */
  int followUpsLeft();

  record Immediate(UUID readingId, QaKind kind, QaTopic topic, int followUpsLeft, QaOutcome outcome)
      implements Prepared {}

  /**
   * A stored, possibly charged message waiting for the model. {@code cacheKey} is null for
   * follow-ups (never cached). {@code toString} is redacted: the prompt holds the question.
   */
  record Job(
      UUID readingId,
      QaKind kind,
      QaTopic topic,
      int followUpsLeft,
      int seq,
      Instant createdAt,
      Prompt prompt,
      List<String> usedKeys,
      String cacheKey)
      implements Prepared {

    public boolean followUp() {
      return seq > 0;
    }

    @Override
    public String toString() {
      return "Job[readingId=" + readingId + ", kind=" + kind + ", seq=" + seq + "]";
    }
  }
}
