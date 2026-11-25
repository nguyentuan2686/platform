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
    int seq = earlier.stream().mapToInt(StoredMessage::seq).max().orElse(-1) + 1;
    if (seq > MAX_SEQ) {
      throw new QaConflictException("follow_ups_exhausted");
    }
    if (!repo.insertMessage(readingId, message.apply(seq), now)) {
      throw new QaConflictException("reading_conflict");
    }
    return new FollowUpSlot.Reserved(seq, earlier);
  }

  /** Stores an answer and, for the first message, marks the reading answered. */
  @Transactional
  public void answer(
      UUID readingId,
      int seq,
      byte[] answerEnc,
      String provider,
      List<String> usedKeys,
      Instant now) {
    repo.answerMessage(readingId, seq, answerEnc, provider, usedKeys, now);
    if (seq == 0) {
      repo.markAnswered(readingId, now);
    }
  }

  /** 409 {@code reading_in_progress} while any reading still waits for an answer or refund. */
  @Transactional
  public void deleteAll(UUID subjectId) {
    if (repo.anyInProgress(subjectId)) {
      throw new QaConflictException("reading_in_progress");
    }
    repo.deleteBySubject(subjectId);
  }
}
