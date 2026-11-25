package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.qa.QaService.Regeneration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The paid-question retry window (spec §14): crash recovery, model retries on the {@link
 * RetryBackoff} schedule until {@code created_at + QA_RETRY_WINDOW}, then a credit refund retried
 * until identity-svc answers.
 *
 * <p>Safe with several replicas: rows are claimed one at a time (lease plus a fresh claim token),
 * each with the clock's current time; the outbound call (model, identity) runs after the claim has
 * committed, outside any transaction; every write after it is a compare-and-set fenced on the claim
 * token, so a row another replica re-claimed after the lease ended is never overwritten by the
 * earlier holder. A rerun is idempotent. Logs carry ids, statuses and error codes only.
 */
public class QaRetryUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(QaRetryUseCase.class);

  /** A charged PENDING reading this old lost its generation (process died); re-queue it. */
  static final Duration STALE = Duration.ofMinutes(10);

  /** An uncharged PENDING reading this old was abandoned mid-request; settle it. */
  static final Duration UNCHARGED_STALE = Duration.ofHours(1);

  /**
   * Lease of one claimed retry: longer than one regeneration in the worst case (four providers at
   * {@code LLM_TIMEOUT} 20 s, twice). The fence keeps a late writer harmless even past it.
   */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

  /** Lease of one claimed refund: longer than identity's 2 s connect + 5 s read timeouts. */
  static final Duration REFUND_LEASE = Duration.ofMinutes(1);

  static final int RETRY_BATCH = 10;
  static final int REFUND_BATCH = 100;
  static final int RECOVERY_BATCH = 100;

  static final String ALL_EXHAUSTED = "all_providers_exhausted";

  private final QaRepositoryPort repo;
  private final QaService qa;
  private final IdentityPort identity;
  private final QaSettings settings;
  private final Clock clock;

  public QaRetryUseCase(
      QaRepositoryPort repo,
      QaService qa,
      IdentityPort identity,
      QaSettings settings,
      Clock clock) {
    this.repo = repo;
    this.qa = qa;
    this.identity = identity;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * Recovery, then up to 10 due retries (stopping early while every model provider is down), then
   * the pending refunds (stopping at the first identity failure).
   */
  public void runOnce() {
    guarded(null, () -> recover(clock.instant()));
    for (int i = 0; i < RETRY_BATCH; i++) {
      Instant now = clock.instant();
      UUID claim = UUID.randomUUID();
      Optional<QaReading> next = repo.claimNextDue(now, now.plus(CLAIM_LEASE), claim);
      if (next.isEmpty()) {
        break;
      }
      Step step = guarded(next.get().id(), () -> retry(next.get(), claim, now));
      if (step == Step.STOP) {
        break;
      }
    }
    for (int i = 0; i < REFUND_BATCH; i++) {
      Instant now = clock.instant();
      UUID claim = UUID.randomUUID();
      Optional<QaReading> next = repo.claimNextRefund(now, now.plus(REFUND_LEASE), claim);
      if (next.isEmpty()) {
        break;
      }
      Step step = guarded(next.get().id(), () -> refund(next.get().id(), claim));
      if (step == Step.STOP) {
        break;
      }
    }
  }

  /** Whether the batch goes on after a row. */
  private enum Step {
    NEXT,
    STOP
  }

  private Step recover(Instant now) {
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
    return Step.NEXT;
  }

  private Step retry(QaReading r, UUID claim, Instant now) {
    UUID id = r.id();
    Instant until =
        r.retryUntil() != null ? r.retryUntil() : r.createdAt().plus(settings.retryWindow());
    if (!now.isBefore(until)) {
      refundPending(id, claim, "retry_window_elapsed");
      return Step.NEXT;
    }
    Regeneration result;
    try {
      result = qa.regenerate(id, claim);
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
      case Regeneration.Declined declined ->
          refundPending(id, claim, "declined:" + declined.reason());
      case Regeneration.NoPrompt noPrompt -> refundPending(id, claim, "no_prompt");
      case Regeneration.Failed failed -> {
        int attempts = r.attempts() + 1;
        // the model call took time: schedule from when it ended
        Instant next = clock.instant().plus(RetryBackoff.next(attempts));
        if (next.isAfter(until)) {
          next = until;
        }
        if (repo.markRetryingClaimed(id, claim, attempts, next, until, failed.code())) {
          LOG.info(
              "qa retry failed reading_id={} attempts={} error={} status=RETRYING",
              id,
              attempts,
              failed.code());
        } else {
          claimLost(id, "RETRYING");
        }
        if (ALL_EXHAUSTED.equals(failed.code())) {
          // every provider is down: the other due rows would only wait out the same timeouts
          return Step.STOP;
        }
      }
    }
    return Step.NEXT;
  }

  private void refundPending(UUID id, UUID claim, String code) {
    if (repo.markRefundPendingClaimed(id, claim, code)) {
      LOG.info("qa retry ended reading_id={} error={} status=REFUND_PENDING", id, code);
    } else {
      claimLost(id, "REFUND_PENDING");
    }
  }

  /**
   * identity's refund is idempotent per reading, so a repeated call never refunds twice. An
   * identity failure stops the batch: the other refunds would only wait out the same timeouts.
   */
  private Step refund(UUID id, UUID claim) {
    RefundOutcome outcome;
    try {
      outcome = identity.refund(id);
    } catch (RuntimeException e) {
      // retried by a later run once the lease ends, with no time limit
      LOG.warn("qa refund pending reading_id={} error={}", id, e.getClass().getSimpleName());
      return Step.STOP;
    }
    if (outcome == RefundOutcome.REFUNDED) {
      if (repo.markRefunded(id, claim, clock.instant())) {
        LOG.info("qa refunded reading_id={} status=REFUNDED", id);
      } else {
        claimLost(id, "REFUNDED");
      }
    } else if (repo.markFailedClaimed(id, claim, "refund:not_charged")) {
      LOG.info("qa refund not charged reading_id={} status=FAILED", id);
    } else {
      claimLost(id, "FAILED");
    }
    return Step.NEXT;
  }

  private static void claimLost(UUID id, String target) {
    LOG.warn("qa job claim lost reading_id={} target={}", id, target);
  }

  /** One row's failure (e.g. a database error) never stops the others. */
  private static Step guarded(UUID id, Supplier<Step> step) {
    try {
      return step.get();
    } catch (RuntimeException e) {
      LOG.warn("qa job step failed reading_id={} error={}", id, e.getClass().getSimpleName());
      return Step.NEXT;
    }
  }
}
