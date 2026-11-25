package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code qa_reading} and {@code qa_message}. Text columns hold ciphertext only. Multi-statement
 * operations are called inside a {@link QaStore} transaction.
 */
public interface QaRepositoryPort {

  /** A stored message; {@code promptEnc} is kept only until the message is answered or refused. */
  record StoredMessage(
      UUID readingId,
      int seq,
      UUID clientMessageId,
      byte[] questionEnc,
      byte[] answerEnc,
      byte[] promptEnc,
      Instant createdAt,
      Instant answeredAt) {}

  /** A message to insert; {@code promptEnc} may be null. */
  record NewMessage(int seq, UUID clientMessageId, byte[] questionEnc, byte[] promptEnc) {}

  /** A reading with its number of follow-up messages. */
  record Listed(QaReading reading, int followUps) {}

  /** Inserts the reading and its first message; false when the reading id already exists. */
  boolean insert(QaReading reading, NewMessage first);

  Optional<QaReading> find(UUID id);

  /** {@code SELECT ... FOR UPDATE}: serialises follow-ups of one reading. */
  Optional<QaReading> findForUpdate(UUID id);

  /**
   * Deletes a reading whose consume was refused; only while it is {@code PENDING} and uncharged.
   * Cascades to its messages.
   */
  boolean deleteUncharged(UUID id);

  // Status transitions: each is a compare-and-set on QaStatus.allowedFrom() of its target and
  // returns false (changing nothing) when the reading is gone or in any other status.

  /** Records the credit source on a {@code PENDING} reading. */
  boolean markCharged(UUID id, String creditSource);

  // Every status change also clears the job claim token. The *Claimed variants are the job writes
  // made after an outbound call: they apply only while the caller still holds its claim
  // (claim_token = claim), so a row re-claimed by another replica after the lease ended is never
  // overwritten by the earlier holder.

  /** Status {@code REFUND_PENDING}, due at once; the stored prompt is dropped. */
  boolean markRefundPending(UUID id, String lastError);

  /**
   * As {@link #markRefundPending(UUID, String)}, but the first refund attempt waits until {@code
   * notBefore} (null: at once).
   */
  boolean markRefundPending(UUID id, String lastError, Instant notBefore);

  /** {@link #markRefundPending(UUID, String)} by the holder of {@code claim}. */
  boolean markRefundPendingClaimed(UUID id, UUID claim, String lastError);

  /** Status {@code REFUSED} with the reason; the stored prompt is dropped. */
  boolean markRefused(UUID id, RefusalReason reason);

  /** Sets {@code refunded_at} once on a {@code REFUSED} reading (its status stays). */
  boolean recordRefund(UUID id, Instant at);

  /**
   * identity-svc reports it never charged this {@code REFUSED} reading (refund 404): clears {@code
   * credit_source} and sets {@code last_error = refund:not_charged}, so the reading is settled (no
   * longer {@link QaReading#owed()}) without claiming a refund happened.
   */
  boolean recordNotCharged(UUID id);

  boolean markRetrying(
      UUID id, int attempts, Instant nextAttemptAt, Instant retryUntil, String lastError);

  /** {@link #markRetrying} by the holder of {@code claim}. */
  boolean markRetryingClaimed(
      UUID id,
      UUID claim,
      int attempts,
      Instant nextAttemptAt,
      Instant retryUntil,
      String lastError);

  /** Status {@code FAILED}; the stored prompt is dropped. */
  boolean markFailed(UUID id, String lastError);

  /** {@link #markFailed} by the holder of {@code claim}. */
  boolean markFailedClaimed(UUID id, UUID claim, String lastError);

  boolean markAnswered(UUID id, Instant at);

  /** {@link #markAnswered} by the holder of {@code claim}. */
  boolean markAnsweredClaimed(UUID id, UUID claim, Instant at);

  /** Stores the answer of one unanswered message and drops its prompt. */
  boolean answerMessage(
      UUID readingId,
      int seq,
      byte[] answerEnc,
      String provider,
      List<String> usedKeys,
      Instant at);

  /** Messages of a reading in {@code seq} order. */
  List<StoredMessage> messages(UUID readingId);

  Optional<StoredMessage> messageByClientId(UUID clientMessageId);

  /** False when the client message id is already used. */
  boolean insertMessage(UUID readingId, NewMessage message, Instant at);

  /** Deletes a follow-up that has no answer yet (an answered one is kept). */
  boolean deleteUnansweredMessage(UUID readingId, int seq);

  /** FREE readings that are {@code PENDING} or {@code ANSWERED}, created at or after since. */
  int countFreeBySubject(UUID subjectId, Instant since);

  /** As {@link #countFreeBySubject}, per chart. */
  int countFreeByChart(String chartHash, Instant since);

  /** As {@link #countFreeBySubject}, for everyone. */
  int countFreeGlobal(Instant since);

  /** Transaction-scoped advisory lock on the chart hash. */
  void lockChart(String chartHash);

  /** Newest first. */
  List<Listed> listBySubject(UUID subjectId, int limit);

  int countFollowUps(UUID readingId);

  /** Any reading still owed an answer or a refund ({@link QaReading#owed()}). */
  boolean anyInProgress(UUID subjectId);

  /**
   * Deletes the subject's readings except those {@link QaReading#owed()}, in one statement, so a
   * question charged concurrently is never deleted. Returns the number deleted.
   */
  int deleteSettledBySubject(UUID subjectId);

  // Jobs (retry, refund, recovery, retention). Safe with several replicas: a claim is one
  // statement (FOR UPDATE SKIP LOCKED) that takes one row, moves next_attempt_at to leaseUntil and
  // stores the claim token, so the outbound call runs after the commit, no other replica takes the
  // row until the lease ends, and the holder's later writes are fenced on the token.

  /**
   * Claims the {@code RETRYING} reading due at {@code now} that has waited longest: lease end
   * {@code leaseUntil}, claim token {@code claim}.
   */
  Optional<QaReading> claimNextDue(Instant now, Instant leaseUntil, UUID claim);

  /** Claims the oldest {@code REFUND_PENDING} reading not leased or deferred at {@code now}. */
  Optional<QaReading> claimNextRefund(Instant now, Instant leaseUntil, UUID claim);

  /**
   * Charged PAID {@code PENDING} readings created before {@code olderThan} (the process died
   * mid-generation) move to {@code RETRYING}, due at {@code now}, within their retry window.
   * Returns the number moved.
   */
  int requeueStalePending(Instant olderThan, Instant now, Duration retryWindow);

  /**
   * Charged PAID {@code REFUSED} readings created before {@code olderThan} whose refund was never
   * recorded (the process died between the refusal and the refund call).
   */
  List<UUID> unrefundedRefusals(Instant olderThan, int limit);

  /** Uncharged {@code PENDING} readings created before {@code olderThan} (abandoned). */
  List<QaReading> staleUncharged(Instant olderThan, int limit);

  /**
   * {@code REFUND_PENDING} to {@code REFUNDED} with {@code refunded_at} by the holder of {@code
   * claim}; drops the prompt.
   */
  boolean markRefunded(UUID id, UUID claim, Instant at);

  /** Deletes FREE readings created before {@code before} that are not owed; cascades. */
  int purgeFree(Instant before);

  /** Deletes answer-cache entries created before {@code before}. */
  int purgeCache(Instant before);
}
