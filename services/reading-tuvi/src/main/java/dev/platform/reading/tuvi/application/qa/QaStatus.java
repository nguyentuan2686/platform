package dev.platform.reading.tuvi.application.qa;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a {@code qa_reading}, with the only allowed transitions. Every status change is a
 * compare-and-set on {@link #allowedFrom()}: a change from any other status is a no-op, so a
 * terminal state (an answer, a refund) is never overwritten by a late or concurrent writer.
 *
 * <pre>
 *   (insert)       -> PENDING | REFUSED (guard)
 *   PENDING        -> ANSWERED | RETRYING | REFUND_PENDING | REFUSED (sentinel) | FAILED (free)
 *   RETRYING       -> ANSWERED | RETRYING | REFUND_PENDING            (retry job)
 *   REFUSED        -> REFUND_PENDING                                  (refund of a refusal failed)
 *   REFUND_PENDING -> REFUNDED | FAILED (identity: never charged)     (retry job)
 *   ANSWERED, REFUNDED, FAILED: terminal
 * </pre>
 */
public enum QaStatus {
  PENDING,
  ANSWERED,
  RETRYING,
  REFUND_PENDING,
  REFUNDED,
  REFUSED,
  FAILED;

  /** Still owed an answer or a refund: never deleted from the history. */
  public static final Set<QaStatus> IN_PROGRESS = EnumSet.of(PENDING, RETRYING, REFUND_PENDING);

  /** The statuses a reading may move to this one from (empty: only by insert). */
  public Set<QaStatus> allowedFrom() {
    return switch (this) {
      case PENDING -> EnumSet.noneOf(QaStatus.class);
      case ANSWERED, RETRYING -> EnumSet.of(PENDING, RETRYING);
      case REFUND_PENDING -> EnumSet.of(PENDING, RETRYING, REFUSED);
      case REFUSED -> EnumSet.of(PENDING);
      case REFUNDED -> EnumSet.of(REFUND_PENDING);
      case FAILED -> EnumSet.of(PENDING, REFUND_PENDING);
    };
  }

  public boolean canMoveTo(QaStatus target) {
    return target.allowedFrom().contains(this);
  }
}
