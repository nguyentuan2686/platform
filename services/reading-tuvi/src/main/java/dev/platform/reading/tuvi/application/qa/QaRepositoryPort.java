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

  /** Deletes the reading and (cascade) its messages. */
  void delete(UUID id);

  void markCharged(UUID id, String creditSource);

  void markRefundPending(UUID id, String lastError);

  /** Status {@code REFUSED} with the reason; the stored prompt is dropped. */
  void markRefused(UUID id, RefusalReason reason);

  /** Sets {@code refunded_at} only (a refused reading keeps its status). */
  void recordRefund(UUID id, Instant at);

  void markRetrying(
      UUID id, int attempts, Instant nextAttemptAt, Instant retryUntil, String lastError);

  void markFailed(UUID id, String lastError);

  void markAnswered(UUID id, Instant at);

  /** Stores the answer of one message and drops its prompt. */
  void answerMessage(
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

  void deleteMessage(UUID readingId, int seq);

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

  /** Any reading in {@code PENDING}, {@code RETRYING} or {@code REFUND_PENDING}. */
  boolean anyInProgress(UUID subjectId);

  void deleteBySubject(UUID subjectId);
}
