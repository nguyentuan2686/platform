package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.RefusalReason;
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

  boolean markRefundPending(UUID id, String lastError);

  /** Status {@code REFUSED} with the reason; the stored prompt is dropped. */
  boolean markRefused(UUID id, RefusalReason reason);

  /** Sets {@code refunded_at} once on a {@code REFUSED} reading (its status stays). */
  boolean recordRefund(UUID id, Instant at);

  boolean markRetrying(
      UUID id, int attempts, Instant nextAttemptAt, Instant retryUntil, String lastError);

  boolean markFailed(UUID id, String lastError);

  boolean markAnswered(UUID id, Instant at);

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
}
