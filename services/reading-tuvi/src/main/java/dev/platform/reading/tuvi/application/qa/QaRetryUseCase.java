package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.qa.QaService.Regeneration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The paid-question retry window (spec §14): crash recovery, model retries on the {@link
 * RetryBackoff} schedule until {@code created_at + QA_RETRY_WINDOW}, then a credit refund retried
 * until identity-svc answers. Every status change is a compare-and-set on the repository; every
 * outbound call (model, identity) runs outside any transaction on a row this run has claimed, so
 * two replicas never work on the same row at once and a rerun is idempotent. Logs carry ids,
 * statuses and error codes only.
 */
public class QaRetryUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(QaRetryUseCase.class);

  /** A charged PENDING reading this old lost its generation (process died); re-queue it. */
  static final Duration STALE = Duration.ofMinutes(10);

  /** An uncharged PENDING reading this old was abandoned mid-request; settle it. */
  static final Duration UNCHARGED_STALE = Duration.ofHours(1);

  /** How long a claimed retry is invisible to other runs (covers the model calls). */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

  /** How long a claimed refund is invisible to other runs (covers the identity call). */
  static final Duration REFUND_LEASE = Duration.ofMinutes(1);

  static final int RETRY_BATCH = 10;
  static final int REFUND_BATCH = 100;
  static final int RECOVERY_BATCH = 100;

  private final QaRepositoryPort repo;
  private final QaService qa;
  private final IdentityPort identity;
  private final QaSettings settings;

  public QaRetryUseCase(
      QaRepositoryPort repo, QaService qa, IdentityPort identity, QaSettings settings) {
    this.repo = repo;
    this.qa = qa;
    this.identity = identity;
    this.settings = settings;
  }

  /** Recovery, then up to 10 due retries, then every pending refund. */
  public void runOnce(Instant now) {
    recover(now);
    for (QaReading r : repo.claimDue(now, now.plus(CLAIM_LEASE), RETRY_BATCH)) {
      guarded(r.id(), () -> retry(r, now));
    }
    for (QaReading r : repo.claimRefunds(now, now.plus(REFUND_LEASE), REFUND_BATCH)) {
      guarded(r.id(), () -> refund(r.id(), now));
    }
  }

  private void recover(Instant now) {
    int requeued = repo.requeueStalePending(now.minus(STALE), now, settings.retryWindow());
    if (requeued > 0) {
      LOG.warn("qa stale pending readings requeued count={}", requeued);
    }
    // refused, charged and never refunded: the refund call was lost
    for (UUID id : repo.unrefundedRefusals(now.minus(STALE), RECOVERY_BATCH)) {
      if (repo.markRefundPending(id, "refund_unrecorded")) {
        LOG.warn("qa unrefunded refusal recovered reading_id={} status=REFUND_PENDING", id);
      }
    }
    for (QaReading r : repo.staleUncharged(now.minus(UNCHARGED_STALE), RECOVERY_BATCH)) {
      if (r.kind() == QaKind.FREE) {
        if (repo.markFailed(r.id(), "stale")) {
          LOG.warn("qa abandoned free chat settled reading_id={} status=FAILED", r.id());
        }
      } else if (repo.markRefundPending(r.id(), "consume_unknown")) {
        // the consume may have charged before the request died: identity decides
        LOG.warn("qa abandoned paid question reading_id={} status=REFUND_PENDING", r.id());
      }
    }
  }

  private void retry(QaReading r, Instant now) {
    UUID id = r.id();
    Instant until =
        r.retryUntil() != null ? r.retryUntil() : r.createdAt().plus(settings.retryWindow());
    if (!now.isBefore(until)) {
      refundPending(id, "retry_window_elapsed");
      return;
    }
    Regeneration result;
    try {
      result = qa.regenerate(id);
    } catch (RuntimeException e) {
      LOG.warn("qa retry failed reading_id={} error={}", id, e.getClass().getSimpleName());
      result = new Regeneration.Failed("internal");
    }
    switch (result) {
      case Regeneration.Answered answered ->
          LOG.info(
              "qa retry answered reading_id={} attempts={} provider={} status=ANSWERED",
              id,
              r.attempts() + 1,
              answered.provider());
      case Regeneration.Settled settled -> LOG.info("qa retry skipped reading_id={}", id);
      case Regeneration.Declined declined -> refundPending(id, "declined:" + declined.reason());
      case Regeneration.NoPrompt noPrompt -> refundPending(id, "no_prompt");
      case Regeneration.Failed failed -> {
        int attempts = r.attempts() + 1;
        Instant next = now.plus(RetryBackoff.next(attempts));
        if (next.isAfter(until)) {
          next = until;
        }
        if (repo.markRetrying(id, attempts, next, until, failed.code())) {
          LOG.info(
              "qa retry failed reading_id={} attempts={} error={} status=RETRYING",
              id,
              attempts,
              failed.code());
        }
      }
    }
  }

  private void refundPending(UUID id, String code) {
    if (repo.markRefundPending(id, code)) {
      LOG.info("qa retry ended reading_id={} error={} status=REFUND_PENDING", id, code);
    }
  }

  /** identity's refund is idempotent per reading, so a repeated call never refunds twice. */
  private void refund(UUID id, Instant now) {
    RefundOutcome outcome;
    try {
      outcome = identity.refund(id);
    } catch (RuntimeException e) {
      // retried by the next run once the lease ends, with no time limit
      LOG.warn("qa refund pending reading_id={} error={}", id, e.getClass().getSimpleName());
      return;
    }
    if (outcome == RefundOutcome.REFUNDED) {
      if (repo.markRefunded(id, now)) {
        LOG.info("qa refunded reading_id={} status=REFUNDED", id);
      }
    } else if (repo.markFailed(id, "refund:not_charged")) {
      LOG.info("qa refund not charged reading_id={} status=FAILED", id);
    }
  }

  /** One row's failure (e.g. a database error) never stops the others. */
  private static void guarded(UUID id, Runnable step) {
    try {
      step.run();
    } catch (RuntimeException e) {
      LOG.warn("qa job step failed reading_id={} error={}", id, e.getClass().getSimpleName());
    }
  }
}
