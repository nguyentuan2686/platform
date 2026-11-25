package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.StoredMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.transaction.annotation.Transactional;

/**
 * The multi-statement database steps of {@link QaService}, each in its own transaction. Never calls
 * another service, so no HTTP call ever runs inside a transaction.
 */
public class QaStore {

  /** Result of inserting a free chat. */
  public enum FreeInsert {
    INSERTED,
    DUPLICATE,
    BUSY
  }

  /** Result of reserving the next follow-up seq. */
  public sealed interface FollowUpSlot {

    /** The message id was already used on this reading. */
    record Replay(StoredMessage message) implements FollowUpSlot {}

    /** {@code seq} is stored; {@code earlier} are the reading's messages before it. */
    record Reserved(int seq, List<StoredMessage> earlier) implements FollowUpSlot {}
  }

  static final int MAX_SEQ = 2;

  private final QaRepositoryPort repo;

  public QaStore(QaRepositoryPort repo) {
    this.repo = repo;
  }

  /** Reading plus first message; false when the id exists (a concurrent duplicate). */
  @Transactional
  public boolean insert(QaReading reading, NewMessage first) {
    return repo.insert(reading, first);
  }

  /**
   * Under an advisory lock on the chart: per-subject and per-chart daily counts (409 {@code
   * free_quota}), then the global cap ({@link FreeInsert#BUSY}), then the insert.
   */
  @Transactional
  public FreeInsert insertFree(
      QaReading reading, NewMessage first, QaSettings settings, Instant since) {
    repo.lockChart(reading.chartHash());
    int limit = settings.freeDailyPerSubject();
    if (repo.countFreeBySubject(reading.subjectId(), since) >= limit
        || repo.countFreeByChart(reading.chartHash(), since) >= limit) {
      throw new QaConflictException("free_quota");
    }
    if (repo.countFreeGlobal(since) >= settings.freeGlobalDailyCap()) {
      return FreeInsert.BUSY;
    }
    return repo.insert(reading, first) ? FreeInsert.INSERTED : FreeInsert.DUPLICATE;
  }

  /**
   * Locks the reading ({@code FOR UPDATE}) and stores the next follow-up message, built by {@code
   * message} for its seq.
   */
  @Transactional
  public FollowUpSlot reserveFollowUp(
      UUID readingId,
      UUID subjectId,
      UUID messageId,
      IntFunction<NewMessage> message,
      Instant now) {
    QaReading reading =
        repo.findForUpdate(readingId)
            .filter(r -> r.subjectId().equals(subjectId))
            .orElseThrow(QaNotFoundException::new);
    Optional<StoredMessage> prior = repo.messageByClientId(messageId);
    if (prior.isPresent()) {
      return new FollowUpSlot.Replay(prior.get());
    }
    if (reading.kind() != QaKind.PAID || reading.status() != QaStatus.ANSWERED) {
      throw new QaConflictException("reading_not_answered");
    }
    List<StoredMessage> earlier = repo.messages(readingId);
    // lowest free follow-up seq: a failed (deleted) follow-up frees its seq again
    int seq = 1;
    while (seq <= MAX_SEQ && hasSeq(earlier, seq)) {
      seq++;
    }
    if (seq > MAX_SEQ) {
      throw new QaConflictException("follow_ups_exhausted");
    }
    if (!repo.insertMessage(readingId, message.apply(seq), now)) {
      throw new QaConflictException("reading_conflict");
    }
    return new FollowUpSlot.Reserved(seq, earlier);
  }

  private static boolean hasSeq(List<StoredMessage> messages, int seq) {
    return messages.stream().anyMatch(m -> m.seq() == seq);
  }

  /**
   * Stores an answer; for the first message the reading moves to {@code ANSWERED} first
   * (compare-and-set). False, with nothing written, when the reading or message can no longer be
   * answered (e.g. already answered, refused or waiting for its refund).
   */
  @Transactional
  public boolean answer(
      UUID readingId,
      int seq,
      byte[] answerEnc,
      String provider,
      List<String> usedKeys,
      Instant now) {
    if (seq == 0 && !repo.markAnswered(readingId, now)) {
      return false;
    }
    if (!repo.answerMessage(readingId, seq, answerEnc, provider, usedKeys, now)) {
      if (seq == 0) {
        // the reading moved but its message is gone or answered: roll the status back
        throw new IllegalStateException("first message cannot be answered");
      }
      return false;
    }
    return true;
  }

  /**
   * Deletes the subject's settled readings. 409 {@code reading_in_progress} when any reading still
   * waits for an answer or refund; the delete itself also skips such readings, so one that becomes
   * in progress concurrently (a paid question being charged) is never deleted.
   */
  @Transactional
  public int deleteAll(UUID subjectId) {
    if (repo.anyInProgress(subjectId)) {
      throw new QaConflictException("reading_in_progress");
    }
    return repo.deleteSettledBySubject(subjectId);
  }
}
