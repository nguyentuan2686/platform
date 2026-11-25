package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code qa_reading} row. {@code subjectKind} is {@code guest} or {@code account}; {@code
 * chartHash} is empty for questions refused before the chart was cast; {@code lastError} holds a
 * code only ({@code all_providers_exhausted}, {@code filter:<rule>}, {@code executor_full}, ...).
 */
public record QaReading(
    UUID id,
    String subjectKind,
    UUID subjectId,
    QaKind kind,
    QaTopic topic,
    String lang,
    String chartHash,
    QaStatus status,
    String refusalReason,
    String creditSource,
    int attempts,
    Instant nextAttemptAt,
    Instant retryUntil,
    String lastError,
    Instant createdAt,
    Instant answeredAt,
    Instant refundedAt) {

  /**
   * Still owed an answer or a refund, so never deletable: {@link QaStatus#IN_PROGRESS}, or a
   * charged PAID reading the model refused whose refund has not been recorded yet (the refund call
   * runs after the refusal is stored). The JDBC adapter mirrors this predicate in SQL.
   */
  public boolean owed() {
    return QaStatus.IN_PROGRESS.contains(status)
        || (kind == QaKind.PAID
            && status == QaStatus.REFUSED
            && creditSource != null
            && refundedAt == null);
  }

  /** A new reading in {@code status}, created at {@code now}. */
  public static QaReading create(
      UUID id,
      IdentitySubject subject,
      QaKind kind,
      QaTopic topic,
      String lang,
      String chartHash,
      QaStatus status,
      String refusalReason,
      Instant now) {
    return new QaReading(
        id,
        subject.kind(),
        subject.id(),
        kind,
        topic,
        lang,
        chartHash,
        status,
        refusalReason,
        null,
        0,
        null,
        null,
        null,
        now,
        null,
        null);
  }

  public QaReading withStatus(QaStatus s) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        s,
        refusalReason,
        creditSource,
        attempts,
        nextAttemptAt,
        retryUntil,
        lastError,
        createdAt,
        answeredAt,
        refundedAt);
  }

  public QaReading withCreditSource(String source) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        status,
        refusalReason,
        source,
        attempts,
        nextAttemptAt,
        retryUntil,
        lastError,
        createdAt,
        answeredAt,
        refundedAt);
  }

  public QaReading withRefusal(String reason) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        status,
        reason,
        creditSource,
        attempts,
        nextAttemptAt,
        retryUntil,
        lastError,
        createdAt,
        answeredAt,
        refundedAt);
  }

  public QaReading withLastError(String error) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        status,
        refusalReason,
        creditSource,
        attempts,
        nextAttemptAt,
        retryUntil,
        error,
        createdAt,
        answeredAt,
        refundedAt);
  }

  public QaReading withRetry(int tries, Instant next, Instant until, String error) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        QaStatus.RETRYING,
        refusalReason,
        creditSource,
        tries,
        next,
        until,
        error,
        createdAt,
        answeredAt,
        refundedAt);
  }

  public QaReading withAnsweredAt(Instant at) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        status,
        refusalReason,
        creditSource,
        attempts,
        nextAttemptAt,
        retryUntil,
        lastError,
        createdAt,
        at,
        refundedAt);
  }

  public QaReading withRefundedAt(Instant at) {
    return new QaReading(
        id,
        subjectKind,
        subjectId,
        kind,
        topic,
        lang,
        chartHash,
        status,
        refusalReason,
        creditSource,
        attempts,
        nextAttemptAt,
        retryUntil,
        lastError,
        createdAt,
        answeredAt,
        at);
  }
}
