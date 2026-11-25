package dev.platform.reading.tuvi.application.qa;

import static dev.platform.reading.tuvi.application.qa.QaFakes.GOOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import dev.platform.reading.tuvi.application.llm.PaidPricing;
import dev.platform.reading.tuvi.application.qa.QaFakes.FakeCharts;
import dev.platform.reading.tuvi.application.qa.QaFakes.FakeCipher;
import dev.platform.reading.tuvi.application.qa.QaFakes.FakeIdentity;
import dev.platform.reading.tuvi.application.qa.QaFakes.FakeKnowledge;
import dev.platform.reading.tuvi.application.qa.QaFakes.MemoryCache;
import dev.platform.reading.tuvi.application.qa.QaFakes.MemoryRepository;
import dev.platform.reading.tuvi.application.qa.QaFakes.MemoryUsage;
import dev.platform.reading.tuvi.application.qa.QaFakes.MutableClock;
import dev.platform.reading.tuvi.application.qa.QaFakes.ScriptedProvider;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QaRetryUseCaseTest {

  static final String Q = "Năm nay chuyện tình duyên của tôi thế nào";
  static final Instant T0 = Instant.parse("2026-11-25T03:00:00Z");
  static final Duration DAY = Duration.ofHours(24);

  final MutableClock clock = new MutableClock(T0);
  final FakeIdentity identity = new FakeIdentity();
  final ScriptedProvider provider = new ScriptedProvider();
  final MemoryRepository repo = new MemoryRepository();
  final FakeCipher cipher = new FakeCipher();
  final FakeKnowledge knowledge = new FakeKnowledge();
  final QaSettings settings = new QaSettings(3, 500, DAY);
  IdentitySubject account;
  QaService qa;
  QaRetryUseCase retry;

  @BeforeEach
  void setUp() {
    account = identity.add("acc", "account");
    LlmGateway gateway =
        new LlmGateway(
            List.of(provider),
            new MemoryUsage(),
            new PaidPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN),
            clock);
    qa =
        new QaService(
            identity,
            new FakeCharts(),
            knowledge,
            gateway,
            repo,
            new QaStore(repo),
            new MemoryCache(),
            cipher,
            new QaFrames(knowledge),
            clock,
            settings);
    retry = new QaRetryUseCase(repo, qa, identity, settings, clock);
  }

  void failModel() {
    provider.fallback =
        () -> {
          throw new LlmUnavailableException("down");
        };
  }

  void healModel() {
    provider.fallback = () -> GOOD;
  }

  /** A charged PAID question whose first generation failed: RETRYING, attempts 1, next +5m. */
  UUID retrying() {
    failModel();
    UUID id = UUID.randomUUID();
    Prepared p = qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    qa.generate((Prepared.Job) p);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.RETRYING);
    return id;
  }

  void runAt(Instant at) {
    clock.now = at;
    retry.runOnce();
  }

  QaReading reading(UUID id) {
    return repo.reading(id);
  }

  void setRetry(UUID id, int attempts, Instant next) {
    QaReading r = reading(id);
    repo.readings.put(id, r.withRetry(attempts, next, r.retryUntil(), r.lastError()));
  }

  @Test
  void retry_succeeds_on_second_attempt() {
    UUID id = retrying();
    healModel();

    runAt(T0.plus(Duration.ofMinutes(4)));
    assertThat(reading(id).status()).isEqualTo(QaStatus.RETRYING);
    assertThat(provider.requests).hasSize(1);

    runAt(T0.plus(Duration.ofMinutes(5)));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(r.answeredAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    assertThat(provider.requests).hasSize(2);
    // the retry sends exactly the stored prompt
    assertThat(provider.requests.get(1)).isEqualTo(provider.requests.get(0));
    var msg = repo.messages(id).getFirst();
    assertThat(cipher.decrypt(id + ":0", msg.answerEnc())).isEqualTo(GOOD);
    assertThat(msg.promptEnc()).isNull();
    assertThat(identity.refunds).isEmpty();
  }

  @Test
  void failed_retry_follows_the_backoff() {
    UUID id = retrying();

    runAt(T0.plus(Duration.ofMinutes(5)));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.attempts()).isEqualTo(2);
    assertThat(r.nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(20)));
    assertThat(r.retryUntil()).isEqualTo(T0.plus(DAY));
    assertThat(r.lastError()).isEqualTo("all_providers_exhausted");
    assertThat(repo.messages(id).getFirst().promptEnc()).isNotNull();

    runAt(T0.plus(Duration.ofMinutes(20)));
    assertThat(reading(id).attempts()).isEqualTo(3);
    assertThat(reading(id).nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(50)));
  }

  @Test
  void answer_at_23h59_no_refund() {
    UUID id = retrying();
    setRetry(id, 9, T0.plus(Duration.ofHours(23).plusMinutes(58)));
    healModel();

    runAt(T0.plus(Duration.ofHours(23).plusMinutes(59)));

    assertThat(reading(id).status()).isEqualTo(QaStatus.ANSWERED);
    runAt(T0.plus(Duration.ofHours(25)));
    assertThat(reading(id).status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(identity.refunds).isEmpty();
  }

  @Test
  void window_elapsed_refunds_once() {
    UUID id = retrying();
    setRetry(id, 9, T0.plus(Duration.ofHours(23)));

    runAt(T0.plus(DAY));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(r.refundedAt()).isEqualTo(T0.plus(DAY));
    assertThat(r.owed()).isFalse();
    assertThat(identity.refunds).containsExactly(id);
    assertThat(repo.messages(id).getFirst().promptEnc()).isNull();
    // the model is never called once the window is over
    assertThat(provider.requests).hasSize(1);

    runAt(T0.plus(DAY).plusSeconds(60));
    runAt(T0.plus(Duration.ofHours(30)));
    assertThat(identity.refunds).containsExactly(id);
  }

  @Test
  void refund_retried_until_identity_answers() {
    UUID id = retrying();
    AtomicInteger calls = new AtomicInteger();
    identity.refund =
        () -> {
          if (calls.incrementAndGet() <= 3) {
            throw new UpstreamUnavailableException("identity");
          }
          return RefundOutcome.REFUNDED;
        };

    Instant t = T0.plus(DAY);
    for (int i = 0; i < 3; i++) {
      runAt(t.plus(Duration.ofMinutes(i)));
      assertThat(reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);
    }
    runAt(t.plus(Duration.ofMinutes(3)));

    assertThat(calls).hasValue(4);
    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUNDED);
    runAt(t.plus(Duration.ofMinutes(4)));
    assertThat(calls).hasValue(4);
  }

  @Test
  void refund_404_marks_failed() {
    UUID id = retrying();
    identity.refund = () -> RefundOutcome.NOT_CHARGED;

    runAt(T0.plus(DAY));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.FAILED);
    assertThat(r.lastError()).isEqualTo("refund:not_charged");
    assertThat(r.refundedAt()).isNull();
    assertThat(r.owed()).isFalse();
    assertThat(repo.messages(id).getFirst().promptEnc()).isNull();
  }

  @Test
  void stuck_pending_is_requeued() {
    healModel();
    UUID charged = UUID.randomUUID();
    // charged, then the process died before the generation ran
    qa.prepare(new AskCommand("acc", charged, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    assertThat(reading(charged).status()).isEqualTo(QaStatus.PENDING);
    assertThat(reading(charged).creditSource()).isEqualTo("CREDIT");
    UUID uncharged = insert(QaKind.PAID, QaStatus.PENDING, null, T0);

    runAt(T0.plus(Duration.ofMinutes(9)));
    assertThat(reading(charged).status()).isEqualTo(QaStatus.PENDING);

    runAt(T0.plus(Duration.ofMinutes(11)));

    assertThat(reading(charged).status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(reading(uncharged).status()).isEqualTo(QaStatus.PENDING);
    assertThat(identity.refunds).isEmpty();
    assertThat(provider.requests).hasSize(1);
  }

  @Test
  void requeued_pending_that_still_fails_keeps_the_24h_window() {
    failModel();
    UUID charged = UUID.randomUUID();
    qa.prepare(new AskCommand("acc", charged, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));

    runAt(T0.plus(Duration.ofMinutes(11)));

    QaReading r = reading(charged);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.retryUntil()).isEqualTo(T0.plus(DAY));
    assertThat(r.attempts()).isEqualTo(2);
    assertThat(r.nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(26)));
  }

  @Test
  void consume_timeout_compensated() {
    identity.consume =
        () -> {
          throw new UpstreamUnavailableException("identity");
        };
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () -> qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi")))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);

    // identity may still be committing the consume: no refund within the grace period
    runAt(T0.plus(Duration.ofMinutes(1)));
    assertThat(identity.refunds).isEmpty();

    runAt(T0.plus(QaSettings.DEFAULT_CONSUME_REFUND_GRACE));

    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUNDED);
  }

  @Test
  void consume_refund_grace_is_configurable() {
    QaSettings slow = new QaSettings(3, 500, DAY, Duration.ofMinutes(10));
    LlmGateway gateway =
        new LlmGateway(
            List.of(provider),
            new MemoryUsage(),
            new PaidPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN),
            clock);
    QaService service =
        new QaService(
            identity,
            new FakeCharts(),
            knowledge,
            gateway,
            repo,
            new QaStore(repo),
            new MemoryCache(),
            cipher,
            new QaFrames(knowledge),
            clock,
            slow);
    retry = new QaRetryUseCase(repo, service, identity, slow, clock);
    identity.consume =
        () -> {
          throw new UpstreamUnavailableException("identity");
        };
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi")))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(reading(id).nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));

    runAt(T0.plus(Duration.ofMinutes(9)));
    assertThat(identity.refunds).isEmpty();
    runAt(T0.plus(Duration.ofMinutes(10)));
    assertThat(identity.refunds).containsExactly(id);
  }

  @Test
  void next_attempt_clamped_to_window() {
    UUID id = retrying();
    setRetry(id, 5, T0.plus(Duration.ofHours(23)));

    runAt(T0.plus(Duration.ofHours(23)));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.attempts()).isEqualTo(6);
    assertThat(r.nextAttemptAt()).isEqualTo(T0.plus(DAY));
  }

  @Test
  void charged_refusal_left_unrefunded_by_a_crash_is_refunded() {
    UUID id = insert(QaKind.PAID, QaStatus.REFUSED, "CREDIT", T0);

    runAt(T0.plus(Duration.ofMinutes(5)));
    assertThat(identity.refunds).isEmpty();

    runAt(T0.plus(Duration.ofMinutes(11)));

    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(reading(id).owed()).isFalse();
  }

  @Test
  void settled_refusals_are_left_alone() {
    UUID refunded = insert(QaKind.PAID, QaStatus.REFUSED, "CREDIT", T0);
    repo.readings.put(refunded, reading(refunded).withRefundedAt(T0));
    UUID guard = insert(QaKind.PAID, QaStatus.REFUSED, null, T0);

    runAt(T0.plus(Duration.ofHours(2)));

    assertThat(identity.refunds).isEmpty();
    assertThat(reading(refunded).status()).isEqualTo(QaStatus.REFUSED);
    assertThat(reading(guard).status()).isEqualTo(QaStatus.REFUSED);
  }

  @Test
  void uncharged_pending_abandoned_for_an_hour_is_settled() {
    UUID free = insert(QaKind.FREE, QaStatus.PENDING, null, T0);
    UUID paid = insert(QaKind.PAID, QaStatus.PENDING, null, T0);
    identity.refund = () -> RefundOutcome.NOT_CHARGED;

    runAt(T0.plus(Duration.ofMinutes(61)));

    assertThat(reading(free).status()).isEqualTo(QaStatus.FAILED);
    assertThat(reading(free).owed()).isFalse();
    // the consume may have charged before the crash: ask identity
    assertThat(identity.refunds).containsExactly(paid);
    assertThat(reading(paid).status()).isEqualTo(QaStatus.FAILED);
    assertThat(reading(paid).owed()).isFalse();
  }

  @Test
  void model_declining_on_retry_refunds() {
    UUID id = retrying();
    provider.fallback = () -> "NGOAI_PHAM_VI";

    runAt(T0.plus(Duration.ofMinutes(5)));

    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(reading(id).lastError()).isEqualTo("declined:OFF_TOPIC");
  }

  @Test
  void unexpected_error_on_retry_counts_as_a_failed_attempt() {
    UUID id = retrying();
    provider.fallback =
        () -> {
          throw new IllegalStateException("bug");
        };

    runAt(T0.plus(Duration.ofMinutes(5)));

    assertThat(reading(id).status()).isEqualTo(QaStatus.RETRYING);
    assertThat(reading(id).attempts()).isEqualTo(2);
    assertThat(reading(id).lastError()).isEqualTo("internal");
  }

  @Test
  void retrying_reading_without_a_prompt_is_refunded() {
    UUID id = retrying();
    var m = repo.messages(id).getFirst();
    repo.messages.set(
        0,
        new QaRepositoryPort.StoredMessage(
            id, 0, m.clientMessageId(), m.questionEnc(), null, null, m.createdAt(), null));

    runAt(T0.plus(Duration.ofMinutes(5)));

    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(reading(id).lastError()).isEqualTo("no_prompt");
  }

  @Test
  void a_claimed_row_is_not_claimed_again_within_its_lease() {
    UUID id = retrying();
    Instant at = T0.plus(Duration.ofMinutes(5));
    Instant lease = at.plus(QaRetryUseCase.CLAIM_LEASE);

    Optional<QaReading> first = repo.claimNextDue(at, lease, UUID.randomUUID());
    Optional<QaReading> second = repo.claimNextDue(at, lease, UUID.randomUUID());

    assertThat(first).map(QaReading::id).contains(id);
    assertThat(second).isEmpty();
    assertThat(repo.claimNextDue(lease, lease.plusSeconds(60), UUID.randomUUID())).isPresent();
  }

  @Test
  void stale_claim_cannot_store_an_answer() {
    UUID id = retrying();
    // while this run waits for the model, its lease ends and another replica claims the row
    provider.fallback =
        () -> {
          repo.claimNextDue(T0.plus(DAY), T0.plus(DAY), UUID.randomUUID());
          return GOOD;
        };

    runAt(T0.plus(Duration.ofMinutes(5)));

    QaReading r = reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.attempts()).isEqualTo(1);
    assertThat(repo.messages(id).getFirst().answerEnc()).isNull();
  }

  @Test
  void stale_claim_cannot_record_a_failure() {
    UUID id = retrying();
    provider.fallback =
        () -> {
          repo.claimNextDue(T0.plus(DAY), T0.plus(DAY), UUID.randomUUID());
          throw new LlmUnavailableException("down");
        };

    runAt(T0.plus(Duration.ofMinutes(5)));

    // the other replica now owns the row: the attempt count and schedule are its to write
    assertThat(reading(id).attempts()).isEqualTo(1);
    assertThat(reading(id).nextAttemptAt()).isEqualTo(T0.plus(DAY));
  }

  @Test
  void stale_claim_cannot_settle_a_refund() {
    UUID id = retrying();
    identity.refund =
        () -> {
          repo.claimNextRefund(
              T0.plus(DAY).plus(Duration.ofMinutes(2)),
              T0.plus(Duration.ofHours(25)),
              UUID.randomUUID());
          return RefundOutcome.REFUNDED;
        };

    runAt(T0.plus(DAY));

    assertThat(identity.refunds).containsExactly(id);
    assertThat(reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);
    identity.refund = () -> RefundOutcome.NOT_CHARGED;
    runAt(T0.plus(DAY).plus(Duration.ofDays(2)));
    // a later claim settles it (identity's ledger is idempotent per reading)
    assertThat(reading(id).status()).isEqualTo(QaStatus.FAILED);
  }

  @Test
  void identity_failure_stops_the_refund_batch() {
    UUID a = retrying();
    UUID b = retrying();
    AtomicInteger calls = new AtomicInteger();
    identity.refund =
        () -> {
          calls.incrementAndGet();
          throw new UpstreamUnavailableException("identity");
        };

    runAt(T0.plus(DAY));

    assertThat(calls).hasValue(1);
    assertThat(reading(a).status()).isEqualTo(QaStatus.REFUND_PENDING);
    assertThat(reading(b).status()).isEqualTo(QaStatus.REFUND_PENDING);

    identity.refund = () -> RefundOutcome.REFUNDED;
    runAt(T0.plus(DAY).plus(Duration.ofMinutes(1)));
    assertThat(reading(a).status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(reading(b).status()).isEqualTo(QaStatus.REFUNDED);
  }

  @Test
  void a_database_error_on_one_refund_does_not_stop_the_others() {
    UUID a = retrying();
    UUID b = retrying();
    AtomicBoolean failed = new AtomicBoolean();
    repo.beforeTransition =
        () -> {
          if (identity.refunds.size() == 1 && failed.compareAndSet(false, true)) {
            throw new IllegalStateException("db down");
          }
        };

    runAt(T0.plus(DAY));

    assertThat(identity.refunds).containsExactlyInAnyOrder(a, b);
    assertThat(List.of(reading(a).status(), reading(b).status()))
        .containsExactlyInAnyOrder(QaStatus.REFUNDED, QaStatus.REFUND_PENDING);
  }

  @Test
  void all_providers_down_stops_the_retry_batch() {
    UUID a = retrying();
    UUID b = retrying();
    int before = provider.requests.size();

    runAt(T0.plus(Duration.ofMinutes(5)));

    assertThat(provider.requests).hasSize(before + 1);
    assertThat(List.of(reading(a).attempts(), reading(b).attempts()))
        .containsExactlyInAnyOrder(1, 2);
  }

  @Test
  void each_row_uses_the_clock_time_of_its_own_turn() {
    UUID id = retrying();
    // the model call takes 3 minutes of clock time
    provider.fallback =
        () -> {
          clock.now = clock.now.plus(Duration.ofMinutes(3));
          throw new LlmUnavailableException("down");
        };

    runAt(T0.plus(Duration.ofMinutes(5)));

    // next = end of the call (+8m) + 15m
    assertThat(reading(id).nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(23)));
  }

  UUID insert(QaKind kind, QaStatus status, String creditSource, Instant created) {
    UUID id = UUID.randomUUID();
    QaReading r =
        QaReading.create(id, account, kind, QaTopic.TinhDuyen, "vi", "h", status, null, created);
    if (status == QaStatus.REFUSED) {
      r = r.withRefusal(RefusalReason.OFF_TOPIC.name());
    }
    repo.insert(r.withCreditSource(creditSource), new NewMessage(0, id, new byte[] {1}, null));
    return id;
  }
}
