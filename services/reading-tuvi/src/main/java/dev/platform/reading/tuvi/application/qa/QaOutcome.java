package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.time.Instant;
import java.util.UUID;

/** What the user is told, ending a stream. Texts are server-side frames or the filtered answer. */
public sealed interface QaOutcome {

  record Answered(UUID readingId, int seq, String text, String disclaimer, int followUpsLeft)
      implements QaOutcome {

    @Override
    public String toString() {
      return "Answered[readingId=" + readingId + ", seq=" + seq + "]";
    }
  }

  record Refused(UUID readingId, RefusalReason reason, String text, String disclaimer)
      implements QaOutcome {}

  /** {@code retryUntil} is null when the reading waits for a refund. */
  record Queued(UUID readingId, Instant retryUntil, String text) implements QaOutcome {}

  record Busy(String text) implements QaOutcome {}
}
