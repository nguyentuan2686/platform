package dev.platform.reading.tuvi.application.qa;

import static dev.platform.reading.tuvi.application.qa.QaFakes.GOOD;
import static dev.platform.reading.tuvi.application.qa.QaFakes.UNSAFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmTier;
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
import dev.platform.reading.tuvi.application.qa.QaOutcome.Answered;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Busy;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Queued;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Refused;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class QaServiceTest {

  static final String Q = "Năm nay chuyện tình duyên của tôi thế nào";
  static final Instant T0 = Instant.parse("2026-11-25T03:00:00Z"); // 10:00 in Vietnam

  final MutableClock clock = new MutableClock(T0);
  final FakeIdentity identity = new FakeIdentity();
  final FakeCharts charts = new FakeCharts();
  final FakeKnowledge knowledge = new FakeKnowledge();
  final ScriptedProvider provider = new ScriptedProvider();
  final MemoryRepository repo = new MemoryRepository();
  final MemoryCache cache = new MemoryCache();
  final FakeCipher cipher = new FakeCipher();
  QaService qa;

  @BeforeEach
  void setUp() {
    identity.add("acc", "account");
    identity.add("guest", "guest");
    identity.add("guest2", "guest");
    LlmGateway gateway =
        new LlmGateway(
            List.of(provider),
            new MemoryUsage(),
            new PaidPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN),
            clock);
    qa =
        new QaService(
            identity,
            charts,
            knowledge,
            gateway,
            repo,
            new QaStore(repo),
            cache,
            cipher,
            new QaFrames(knowledge),
            clock,
            new QaSettings(3, 500, Duration.ofHours(24)));
  }

  QaOutcome run(Prepared p) {
    return switch (p) {
      case Prepared.Immediate i -> i.outcome();
      case Prepared.Job j -> qa.generate(j);
    };
  }

  QaOutcome ask(String bearer, UUID id, QaKind kind, String question) {
    return run(qa.prepare(new AskCommand(bearer, id, kind, QaTopic.TinhDuyen, question, "vi")));
  }

  QaOutcome paid(UUID id) {
    return ask("acc", id, QaKind.PAID, Q);
  }

  QaOutcome followUp(UUID id, String question) {
    return run(qa.prepareFollowUp(new FollowUpCommand("acc", id, UUID.randomUUID(), question)));
  }

  static String reason(Throwable t) {
    return ((QaConflictException) t).reason();
  }

  // ---- PAID ----

  @Test
  void paid_happy_path_charges_then_answers() {
    UUID id = UUID.randomUUID();

    QaOutcome out = paid(id);

    assertThat(out).isInstanceOf(Answered.class);
    Answered a = (Answered) out;
    assertThat(a.text()).isEqualTo(GOOD);
    assertThat(a.followUpsLeft()).isEqualTo(2);
    assertThat(a.seq()).isZero();
    assertThat(a.disclaimer()).isEqualTo("LỜI-NHẮC");
    assertThat(identity.consumed).containsExactly(id + ":TinhDuyen");
    QaReading r = repo.reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(r.creditSource()).isEqualTo("CREDIT");
    assertThat(r.answeredAt()).isEqualTo(T0);
    assertThat(provider.requests.get(0).tier()).isEqualTo(LlmTier.ANY);
    assertThat(provider.requests.get(0).temperature()).isEqualTo(0.6);
    // stored encrypted under the message AAD, never as plain text
    var msg = repo.messages(id).get(0);
    assertThat(cipher.decrypt(id + ":0", msg.answerEnc())).isEqualTo(GOOD);
    assertThat(cipher.decrypt(id + ":0", msg.questionEnc())).isEqualTo(Q);
    assertThat(msg.promptEnc()).isNull();
  }

  @Test
  void same_reading_id_twice_charges_once() {
    UUID id = UUID.randomUUID();
    paid(id);

    QaOutcome again = paid(id);

    assertThat(again).isInstanceOf(Answered.class);
    assertThat(((Answered) again).text()).isEqualTo(GOOD);
    assertThat(identity.consumed).hasSize(1);
    assertThat(provider.requests).hasSize(1);
  }

  @Test
  void guest_paid_is_account_required() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> ask("guest", id, QaKind.PAID, Q))
        .isInstanceOf(AccountRequiredException.class);
    assertThat(repo.readings).isEmpty();
  }

  @Test
  void guard_refusal_not_charged() {
    UUID id = UUID.randomUUID();

    QaOutcome out = ask("acc", id, QaKind.PAID, "Tôi nên uống thuốc gì cho khỏe");

    assertThat(out).isInstanceOf(Refused.class);
    assertThat(((Refused) out).reason()).isEqualTo(RefusalReason.MEDICAL);
    assertThat(((Refused) out).text()).isEqualTo("TỪ-CHỐI-medical");
    assertThat(((Refused) out).disclaimer()).isEqualTo("LỜI-NHẮC");
    assertThat(identity.consumed).isEmpty();
    assertThat(provider.requests).isEmpty();
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUSED);
    // replay of a refused reading
    assertThat(ask("acc", id, QaKind.PAID, "Tôi nên uống thuốc gì cho khỏe"))
        .isInstanceOf(Refused.class);
  }

  @Test
  void no_profile_409() {
    identity.profiles.remove("acc");

    assertThatThrownBy(() -> paid(UUID.randomUUID()))
        .isInstanceOf(QaConflictException.class)
        .satisfies(t -> assertThat(reason(t)).isEqualTo("no_profile"));
    assertThat(identity.consumed).isEmpty();
  }

  @Test
  void chart_down_503_not_charged() {
    charts.down = true;

    assertThatThrownBy(() -> paid(UUID.randomUUID()))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(identity.consumed).isEmpty();
    assertThat(repo.readings).isEmpty();
  }

  @Test
  void no_credits_409_and_reading_deleted() {
    identity.consume = () -> new ConsumeOutcome.Refused("no_credits");
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> paid(id))
        .isInstanceOf(QaConflictException.class)
        .satisfies(t -> assertThat(reason(t)).isEqualTo("no_credits"));
    assertThat(repo.readings).isEmpty();
    assertThat(repo.messages).isEmpty();
    assertThat(provider.requests).isEmpty();
  }

  @Test
  void consume_timeout_sets_refund_pending_and_503() {
    identity.consume =
        () -> {
          throw new UpstreamUnavailableException("identity");
        };
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> paid(id))
        .isInstanceOf(UpstreamUnavailableException.class)
        .satisfies(
            t -> assertThat(((UpstreamUnavailableException) t).upstream()).isEqualTo("identity"));
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);
    assertThat(provider.requests).isEmpty();
    // a retry of the same id is told to wait, never charged twice
    assertThat(paid(id)).isInstanceOf(Queued.class);
    assertThat(identity.consumed).hasSize(1);
  }

  /** The decline log names the provider, so a prod refusal can be traced to a model. */
  @Test
  void model_decline_log_names_the_provider(
      org.springframework.boot.test.system.CapturedOutput out) {
    provider.then("NGOAI_PHAM_VI");

    paid(UUID.randomUUID());

    assertThat(out.getAll())
        .contains("qa model declined")
        .contains("reason=OFF_TOPIC provider=scripted");
  }

  @Test
  void sentinel_offtopic_refunds() {
    provider.then("NGOAI_PHAM_VI");
    UUID id = UUID.randomUUID();

    QaOutcome out = paid(id);

    assertThat(out).isInstanceOf(Refused.class);
    assertThat(((Refused) out).reason()).isEqualTo(RefusalReason.OFF_TOPIC);
    assertThat(((Refused) out).text()).isEqualTo("NGOÀI-LỀ");
    assertThat(identity.refunds).containsExactly(id);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUSED);
    assertThat(repo.reading(id).refusalReason()).isEqualTo("OFF_TOPIC");
    assertThat(repo.reading(id).refundedAt()).isEqualTo(T0);
  }

  @Test
  void sentinel_insufficient_refunds() {
    provider.then("**KHONG_DU_TU_LIEU**");
    UUID id = UUID.randomUUID();

    QaOutcome out = paid(id);

    assertThat(((Refused) out).reason()).isEqualTo(RefusalReason.INSUFFICIENT);
    assertThat(((Refused) out).text()).isEqualTo("CHƯA-ĐỦ");
    assertThat(identity.refunds).containsExactly(id);
  }

  @Test
  void sentinel_refund_failure_leaves_refund_pending() {
    provider.then("NGOAI_PHAM_VI");
    identity.refund =
        () -> {
          throw new UpstreamUnavailableException("identity");
        };
    UUID id = UUID.randomUUID();

    assertThat(paid(id)).isInstanceOf(Refused.class);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);
  }

  @Test
  void unsafe_twice_queues_paid() {
    provider.then(UNSAFE);
    provider.then(UNSAFE);
    UUID id = UUID.randomUUID();

    QaOutcome out = paid(id);

    assertThat(out).isInstanceOf(Queued.class);
    assertThat(((Queued) out).text()).isEqualTo("ĐANG-NGẪM");
    assertThat(provider.requests).hasSize(2);
    // the regeneration uses the same prompt
    assertThat(provider.requests.get(1)).isEqualTo(provider.requests.get(0));
    QaReading r = repo.reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.attempts()).isEqualTo(1);
    assertThat(r.lastError()).isEqualTo("filter:banned");
    assertThat(identity.refunds).isEmpty();
    // the prompt is kept (encrypted) for the retry job
    assertThat(repo.messages(id).get(0).promptEnc()).isNotNull();
  }

  @Test
  void unsafe_once_then_clean_answers() {
    provider.then(UNSAFE);

    assertThat(paid(UUID.randomUUID())).isInstanceOf(Answered.class);
    assertThat(provider.requests).hasSize(2);
  }

  @Test
  void all_exhausted_queues_paid_with_retry_until_24h() {
    provider.thenFail();
    UUID id = UUID.randomUUID();

    QaOutcome out = paid(id);

    assertThat(out).isInstanceOf(Queued.class);
    assertThat(((Queued) out).retryUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));
    QaReading r = repo.reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    assertThat(r.retryUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));
    assertThat(r.lastError()).isEqualTo("all_providers_exhausted");
    // replay while retrying
    assertThat(paid(id)).isInstanceOf(Queued.class);
    assertThat(identity.consumed).hasSize(1);
  }

  @Test
  void executor_rejection_after_charging_queues() {
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));

    QaOutcome out = qa.rejected(job);

    assertThat(out).isInstanceOf(Queued.class);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.RETRYING);
    assertThat(repo.reading(id).lastError()).isEqualTo("executor_full");
  }

  @Test
  void unexpected_error_during_generation_retries_a_charged_reading() {
    provider.fallback =
        () -> {
          throw new IllegalStateException("bug");
        };
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> paid(id)).isInstanceOf(IllegalStateException.class);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.RETRYING);
    assertThat(repo.reading(id).lastError()).isEqualTo("internal");
  }

  @Test
  void existing_reading_states() {
    UUID pending = UUID.randomUUID();
    qa.prepare(new AskCommand("acc", pending, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));

    assertThatThrownBy(() -> paid(pending))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_in_progress"));
    assertThatThrownBy(() -> ask("guest", pending, QaKind.FREE, Q))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_conflict"));

    repo.markRefundPending(pending, "x");
    repo.readings.put(pending, repo.reading(pending).withStatus(QaStatus.REFUNDED));
    assertThatThrownBy(() -> paid(pending))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_refunded"));
    repo.readings.put(pending, repo.reading(pending).withStatus(QaStatus.FAILED));
    assertThatThrownBy(() -> paid(pending))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_refunded"));
    assertThat(identity.consumed).hasSize(1);
  }

  @Test
  void bad_lang_and_bad_question_are_400() {
    assertThatThrownBy(
            () ->
                qa.prepare(
                    new AskCommand(
                        "acc", UUID.randomUUID(), QaKind.PAID, QaTopic.TinhDuyen, Q, "en")))
        .isInstanceOf(QaInvalidRequestException.class)
        .satisfies(t -> assertThat(((QaInvalidRequestException) t).field()).isEqualTo("lang"));
    assertThatThrownBy(() -> ask("acc", UUID.randomUUID(), QaKind.PAID, "hi"))
        .isInstanceOf(QaInvalidRequestException.class)
        .satisfies(t -> assertThat(((QaInvalidRequestException) t).field()).isEqualTo("question"));
  }

  @Test
  void van_nam_reads_the_current_year_from_chart_svc() {
    run(
        qa.prepare(
            new AskCommand(
                "acc",
                UUID.randomUUID(),
                QaKind.PAID,
                QaTopic.VanNam,
                "Vận năm nay ra sao",
                "vi")));

    assertThat(charts.dailyCalls).isEqualTo(1);
    String user = provider.requests.get(0).messages().getLast().content();
    assertThat(user).contains("Năm nay lưu niên ở cung");
  }

  private String lastUser() {
    return provider.requests.getLast().messages().getLast().content();
  }

  @Test
  void timing_question_gets_thoi_van_in_calendar_years_without_a_daily_call() {
    ask("acc", UUID.randomUUID(), QaKind.PAID, "Khi nào tôi gặp được người hợp?");

    String user = lastUser();
    // fixture: lunar birth year 1994, 2026 is nominal age 33 -> Phúc Đức (26-35)
    assertThat(user)
        .contains(
            "THỜI VẬN:\n- Đại hạn hiện tại (năm 2026): cung Phúc Đức, năm 2019–2028\n"
                + "- Đại hạn cung Phu Thê: năm 2099–2108\n"
                + "- Lưu niên vào cung Phu Thê: năm 2029 (Kỷ Dậu)\n"
                + "TƯ LIỆU:");
    assertThat(user.indexOf("THỜI VẬN:")).isGreaterThan(user.indexOf("LÁ SỐ:"));
    assertThat(user).doesNotContain("1994", "08:30", "male", "tuổi");
    assertThat(charts.dailyCalls).isZero();
  }

  @Test
  void a_named_year_reads_that_years_flow_layer_for_any_topic() {
    run(
        qa.prepare(
            new AskCommand(
                "acc",
                UUID.randomUUID(),
                QaKind.PAID,
                QaTopic.SuNghiep,
                "Năm 2027 công việc thế nào?",
                "vi")));

    assertThat(charts.dailyDates).containsExactly(java.time.LocalDate.of(2027, 7, 1));
    assertThat(lastUser())
        .contains("- Năm 2027 lưu niên ở cung Quan Lộc")
        .doesNotContain("THỜI VẬN");
  }

  @Test
  void follow_up_uses_its_own_timing_and_year() {
    UUID id = UUID.randomUUID();
    paid(id);
    int before = charts.dailyCalls;

    followUp(id, "Bao giờ tôi lấy vợ?");
    assertThat(lastUser()).contains("THỜI VẬN:", "Lưu niên vào cung Phu Thê: năm 2029");
    assertThat(charts.dailyCalls).isEqualTo(before);

    followUp(id, "Vậy sang năm thì sao");
    assertThat(charts.dailyDates.getLast()).isEqualTo(java.time.LocalDate.of(2027, 7, 1));
    assertThat(lastUser()).contains("Năm 2027 lưu niên ở cung").doesNotContain("THỜI VẬN");
  }

  // ---- FREE ----

  @Test
  void free_uses_free_only_tier() {
    UUID id = UUID.randomUUID();

    QaOutcome out = ask("guest", id, QaKind.FREE, Q);

    assertThat(out).isInstanceOf(Answered.class);
    assertThat(((Answered) out).followUpsLeft()).isZero();
    assertThat(provider.requests.get(0).tier()).isEqualTo(LlmTier.FREE_ONLY);
    assertThat(identity.consumed).isEmpty();
    assertThat(repo.locks).contains("chart:hash-A");
  }

  @Test
  void free_fourth_today_is_free_quota() {
    for (int i = 0; i < 3; i++) {
      assertThat(ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " " + i))
          .isInstanceOf(Answered.class);
    }

    assertThatThrownBy(() -> ask("guest", UUID.randomUUID(), QaKind.FREE, Q))
        .isInstanceOf(QaConflictException.class)
        .satisfies(t -> assertThat(reason(t)).isEqualTo("free_quota"));
    assertThat(qa.freeQuota("guest")).isEqualTo(new FreeQuota(3, 3, 0));
  }

  @Test
  void free_new_guest_same_chart_still_capped() {
    for (int i = 0; i < 3; i++) {
      ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " " + i);
    }

    assertThatThrownBy(() -> ask("guest2", UUID.randomUUID(), QaKind.FREE, Q))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("free_quota"));
    assertThat(qa.freeQuota("guest2")).isEqualTo(new FreeQuota(3, 0, 3));
  }

  @Test
  void free_quota_resets_at_vn_midnight() {
    clock.now = Instant.parse("2026-11-25T16:30:00Z"); // 23:30 in Vietnam
    for (int i = 0; i < 3; i++) {
      ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " " + i);
    }
    clock.now = Instant.parse("2026-11-25T16:59:00Z");
    assertThatThrownBy(() -> ask("guest", UUID.randomUUID(), QaKind.FREE, Q))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("free_quota"));

    clock.now = Instant.parse("2026-11-25T17:01:00Z"); // 00:01 next day in Vietnam

    assertThat(ask("guest", UUID.randomUUID(), QaKind.FREE, Q)).isInstanceOf(Answered.class);
  }

  @Test
  void free_failure_not_counted_and_busy() {
    provider.thenFail();
    UUID failed = UUID.randomUUID();

    QaOutcome out = ask("guest", failed, QaKind.FREE, Q);

    assertThat(out).isInstanceOf(Busy.class);
    assertThat(((Busy) out).text()).isEqualTo("MỎI-RỒI");
    assertThat(repo.reading(failed).status()).isEqualTo(QaStatus.FAILED);
    for (int i = 0; i < 3; i++) {
      assertThat(ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " " + i))
          .isInstanceOf(Answered.class);
    }
  }

  @Test
  void free_global_cap_is_busy() {
    qa =
        new QaService(
            identity,
            charts,
            knowledge,
            new LlmGateway(
                List.of(provider),
                new MemoryUsage(),
                new PaidPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN),
                clock),
            repo,
            new QaStore(repo),
            cache,
            cipher,
            new QaFrames(knowledge),
            clock,
            new QaSettings(3, 1, Duration.ofHours(24)));
    ask("guest", UUID.randomUUID(), QaKind.FREE, Q);
    UUID id = UUID.randomUUID();

    assertThat(ask("guest2", id, QaKind.FREE, Q + " khác")).isInstanceOf(Busy.class);
    assertThat(repo.reading(id)).isNull();
  }

  @Test
  void free_model_declined_chats_count_toward_the_daily_quota() {
    // final review I-1: a declined chat still cost a provider call
    for (int i = 0; i < 3; i++) {
      provider.then(i % 2 == 0 ? "NGOAI_PHAM_VI" : "KHONG_DU_TU_LIEU");
      assertThat(ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " " + i))
          .isInstanceOf(Refused.class);
    }

    assertThatThrownBy(() -> ask("guest", UUID.randomUUID(), QaKind.FREE, Q))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("free_quota"));
    assertThat(qa.freeQuota("guest")).isEqualTo(new FreeQuota(3, 3, 0));
    // the per-chart cap counts them too
    assertThatThrownBy(() -> ask("guest2", UUID.randomUUID(), QaKind.FREE, Q))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("free_quota"));
    assertThat(provider.requests).hasSize(3);
  }

  @Test
  void free_guard_refusals_never_reach_the_model_and_are_not_counted() {
    for (int i = 0; i < 3; i++) {
      assertThat(ask("guest", UUID.randomUUID(), QaKind.FREE, "Tôi nên uống thuốc gì " + i))
          .isInstanceOf(Refused.class);
    }

    assertThat(qa.freeQuota("guest")).isEqualTo(new FreeQuota(3, 0, 3));
    assertThat(provider.requests).isEmpty();
  }

  @Test
  void free_declined_and_failed_chats_count_toward_the_global_cap() {
    qa =
        new QaService(
            identity,
            charts,
            knowledge,
            new LlmGateway(
                List.of(provider),
                new MemoryUsage(),
                new PaidPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN),
                clock),
            repo,
            new QaStore(repo),
            cache,
            cipher,
            new QaFrames(knowledge),
            clock,
            new QaSettings(3, 2, Duration.ofHours(24)));
    provider.then("NGOAI_PHAM_VI");
    ask("guest", UUID.randomUUID(), QaKind.FREE, Q);
    provider.thenFail();
    ask("guest", UUID.randomUUID(), QaKind.FREE, Q + " hai");

    assertThat(ask("guest2", UUID.randomUUID(), QaKind.FREE, Q + " ba")).isInstanceOf(Busy.class);
  }

  @Test
  void free_sentinel_is_refused_without_refund() {
    provider.then("NGOAI_PHAM_VI");
    UUID id = UUID.randomUUID();

    assertThat(ask("guest", id, QaKind.FREE, Q)).isInstanceOf(Refused.class);
    assertThat(identity.refunds).isEmpty();
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUSED);
  }

  // ---- follow-ups ----

  @Test
  void follow_up_two_then_exhausted() {
    UUID id = UUID.randomUUID();
    paid(id);

    QaOutcome first = followUp(id, "Vậy năm sau thì sao");
    QaOutcome second = followUp(id, "Còn chuyện gia đình thì thế nào");

    assertThat(((Answered) first).seq()).isEqualTo(1);
    assertThat(((Answered) first).followUpsLeft()).isEqualTo(1);
    assertThat(((Answered) second).seq()).isEqualTo(2);
    assertThat(((Answered) second).followUpsLeft()).isZero();
    // earlier turns are in the prompt
    List<LlmMessage> msgs = provider.requests.get(2).messages();
    assertThat(msgs.stream().filter(m -> m.role() == LlmMessage.Role.ASSISTANT)).hasSize(2);
    assertThat(msgs.get(1).content()).contains(Q);
    assertThatThrownBy(() -> followUp(id, "Thêm một câu nữa được không"))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("follow_ups_exhausted"));
    assertThat(identity.consumed).hasSize(1);
    assertThat(repo.locks).contains("reading:" + id);

    ReadingDetail detail = qa.get("acc", id);
    assertThat(detail.messages()).hasSize(3);
    assertThat(detail.followUpsLeft()).isZero();
    assertThat(detail.messages().get(1).question()).isEqualTo("Vậy năm sau thì sao");
    assertThat(detail.messages().get(1).answer()).isEqualTo(GOOD);
  }

  @Test
  void follow_up_repeat_message_id_replays() {
    UUID id = UUID.randomUUID();
    paid(id);
    UUID msg = UUID.randomUUID();
    FollowUpCommand cmd = new FollowUpCommand("acc", id, msg, "Vậy năm sau thì sao");
    run(qa.prepareFollowUp(cmd));

    QaOutcome again = run(qa.prepareFollowUp(cmd));

    assertThat(((Answered) again).seq()).isEqualTo(1);
    assertThat(provider.requests).hasSize(2);
  }

  @Test
  void follow_up_on_free_rejected() {
    UUID id = UUID.randomUUID();
    ask("acc", id, QaKind.FREE, Q);

    assertThatThrownBy(() -> followUp(id, "Vậy năm sau thì sao"))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_not_answered"));
  }

  @Test
  void follow_up_on_another_subjects_reading_is_404() {
    UUID id = UUID.randomUUID();
    ask("guest", id, QaKind.FREE, Q);

    assertThatThrownBy(() -> followUp(id, "Vậy năm sau thì sao"))
        .isInstanceOf(QaNotFoundException.class);
    assertThatThrownBy(() -> followUp(UUID.randomUUID(), "Vậy năm sau thì sao"))
        .isInstanceOf(QaNotFoundException.class);
    assertThatThrownBy(() -> qa.get("acc", id)).isInstanceOf(QaNotFoundException.class);
  }

  @Test
  void follow_up_guard_refusal_not_counted() {
    UUID id = UUID.randomUUID();
    paid(id);

    QaOutcome out = followUp(id, "Tôi có nên đánh đề không");

    assertThat(((Refused) out).reason()).isEqualTo(RefusalReason.GAMBLING);
    assertThat(repo.countFollowUps(id)).isZero();
  }

  @Test
  void follow_up_failure_not_counted() {
    UUID id = UUID.randomUUID();
    paid(id);
    provider.thenFail();

    QaOutcome failed = followUp(id, "Vậy năm sau thì sao");

    assertThat(failed).isInstanceOf(Busy.class);
    assertThat(repo.countFollowUps(id)).isZero();
    assertThat(((Answered) followUp(id, "Vậy năm sau thì sao")).seq()).isEqualTo(1);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.ANSWERED);
  }

  // ---- cache ----

  @Test
  void cache_hit_skips_gateway() {
    paid(UUID.randomUUID());
    UUID second = UUID.randomUUID();

    QaOutcome out = paid(second);

    assertThat(((Answered) out).text()).isEqualTo(GOOD);
    assertThat(provider.requests).hasSize(1);
    assertThat(identity.consumed).hasSize(2);
    assertThat(repo.reading(second).status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(cache.entries).hasSize(1);
  }

  @Test
  void different_kind_not_cache_shared() {
    paid(UUID.randomUUID());

    ask("acc", UUID.randomUUID(), QaKind.FREE, Q);

    assertThat(provider.requests).hasSize(2);
    assertThat(cache.entries).hasSize(2);
  }

  // ---- history ----

  @Test
  void list_get_and_delete_all() {
    UUID a = UUID.randomUUID();
    paid(a);
    clock.now = T0.plusSeconds(60);
    provider.thenFail();
    UUID b = UUID.randomUUID();
    ask("acc", b, QaKind.PAID, "Người yêu của tôi năm nay ra sao"); // not a cache hit

    List<ReadingSummary> list = qa.list("acc");
    assertThat(list).extracting(ReadingSummary::readingId).containsExactly(b, a);
    assertThat(list.get(1).followUpsLeft()).isEqualTo(2);
    assertThat(list.get(0).status()).isEqualTo(QaStatus.RETRYING);

    ReadingDetail queued = qa.get("acc", b);
    assertThat(queued.notice()).isEqualTo("ĐANG-NGẪM");
    assertThat(queued.retryUntil()).isEqualTo(T0.plusSeconds(60).plus(Duration.ofHours(24)));
    assertThat(queued.messages().get(0).answer()).isNull();
    assertThat(queued.disclaimer()).isEqualTo("LỜI-NHẮC");
    assertThat(qa.get("acc", a).notice()).isNull();

    assertThatThrownBy(() -> qa.deleteAll("acc"))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_in_progress"));
    repo.readings.put(b, repo.reading(b).withStatus(QaStatus.REFUNDED));
    assertThat(qa.get("acc", b).notice()).isEqualTo("ĐÃ-TRẢ");

    qa.deleteAll("acc");

    assertThat(qa.list("acc")).isEmpty();
  }

  // ---- status transitions are compare-and-set ----

  @Test
  void delete_racing_in_flight_paid_question_keeps_charged_reading() {
    UUID settled = UUID.randomUUID();
    paid(settled);
    UUID id = UUID.randomUUID();
    // the DELETE's statement lands after the insert, before the charge is recorded
    identity.consume =
        () -> {
          repo.deleteSettledBySubject(repo.reading(settled).subjectId());
          return new ConsumeOutcome.Charged("CREDIT");
        };

    QaOutcome out = paid(id);

    assertThat(out).isInstanceOf(Answered.class);
    assertThat(repo.reading(settled)).isNull();
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(repo.reading(id).creditSource()).isEqualTo("CREDIT");
    assertThat(identity.refunds).isEmpty();
  }

  @Test
  void delete_during_refund_of_paid_refusal_keeps_row_then_refund_pending() {
    provider.then("NGOAI_PHAM_VI");
    UUID id = UUID.randomUUID();
    List<Integer> deleted = new java.util.ArrayList<>();
    identity.refund =
        () -> {
          // the history DELETE lands while the refund call is in flight
          QaReading r = repo.reading(id);
          assertThat(r.status()).isEqualTo(QaStatus.REFUSED);
          assertThat(repo.anyInProgress(r.subjectId())).isTrue();
          deleted.add(repo.deleteSettledBySubject(r.subjectId()));
          throw new UpstreamUnavailableException("identity");
        };

    assertThat(paid(id)).isInstanceOf(Refused.class);

    assertThat(deleted).containsExactly(0);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUND_PENDING);
  }

  @Test
  void refunded_refusal_is_settled_and_never_pending_again() {
    provider.then("NGOAI_PHAM_VI");
    UUID id = UUID.randomUUID();
    paid(id);
    UUID subject = repo.reading(id).subjectId();

    assertThat(repo.markRefundPending(id, "x")).isFalse();
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUSED);
    assertThat(repo.anyInProgress(subject)).isFalse();
    qa.deleteAll("acc");
    assertThat(repo.reading(id)).isNull();
  }

  @Test
  void not_charged_refusal_is_settled_and_deletable() {
    provider.then("NGOAI_PHAM_VI");
    identity.refund = () -> RefundOutcome.NOT_CHARGED;
    UUID id = UUID.randomUUID();

    assertThat(paid(id)).isInstanceOf(Refused.class);

    QaReading r = repo.reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.REFUSED);
    assertThat(r.creditSource()).isNull();
    assertThat(r.refundedAt()).isNull(); // no refund is claimed
    assertThat(r.lastError()).isEqualTo("refund:not_charged");
    assertThat(r.owed()).isFalse();
    qa.deleteAll("acc");
    assertThat(repo.reading(id)).isNull();
  }

  @Test
  void deleted_paid_reading_id_cannot_be_reused() {
    // final review C-1: identity answers a repeated reading id with 200 and no new debit
    UUID id = UUID.randomUUID();
    paid(id);
    qa.deleteAll("acc");
    assertThat(repo.reading(id)).isNull();

    for (QaKind kind : QaKind.values()) {
      assertThatThrownBy(() -> ask("acc", id, kind, "Năm nay sự nghiệp của tôi thế nào"))
          .isInstanceOf(QaConflictException.class)
          .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_conflict"));
    }
    assertThat(identity.consumed).hasSize(1);
    assertThat(provider.requests).hasSize(1);
    assertThat(repo.reading(id)).isNull();
  }

  @Test
  void deleted_free_reading_leaves_no_tombstone() {
    UUID id = UUID.randomUUID();
    ask("guest", id, QaKind.FREE, Q);
    qa.deleteAll("guest");

    assertThat(repo.tombstones).isEmpty();
  }

  @Test
  void guard_refused_paid_reading_was_never_charged_and_is_deletable() {
    UUID id = UUID.randomUUID();
    ask("acc", id, QaKind.PAID, "Tôi nên uống thuốc gì cho khỏe");

    qa.deleteAll("acc");

    assertThat(repo.reading(id)).isNull();
  }

  @Test
  void charged_reading_whose_row_vanished_is_refunded_not_kept() {
    UUID id = UUID.randomUUID();
    identity.consume =
        () -> {
          repo.remove(id); // e.g. an operator delete: no row left to answer or refund
          return new ConsumeOutcome.Charged("CREDIT");
        };

    assertThatThrownBy(() -> paid(id))
        .satisfies(t -> assertThat(reason(t)).isEqualTo("reading_in_progress"));
    assertThat(identity.refunds).containsExactly(id);
  }

  @Test
  void catch_all_after_answered_does_not_flip_to_retrying() {
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    knowledge.failFrames = true; // the disclaimer lookup fails after the answer is stored

    assertThatThrownBy(() -> qa.generate(job)).isInstanceOf(IllegalStateException.class);

    QaReading r = repo.reading(id);
    assertThat(r.status()).isEqualTo(QaStatus.ANSWERED);
    assertThat(r.lastError()).isNull();
    assertThat(cipher.decrypt(id + ":0", repo.messages(id).get(0).answerEnc())).isEqualTo(GOOD);
  }

  @Test
  void catch_all_after_refunded_refusal_does_not_flip_to_retrying() {
    provider.then("NGOAI_PHAM_VI");
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    knowledge.failFrames = true;

    assertThatThrownBy(() -> qa.generate(job)).isInstanceOf(IllegalStateException.class);

    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.REFUSED);
    assertThat(repo.reading(id).refundedAt()).isEqualTo(T0);
    assertThat(identity.refunds).containsExactly(id);
  }

  @Test
  void failure_on_a_reading_answered_meanwhile_reports_the_answer() {
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    qa.generate(job); // e.g. the retry job answered it

    QaOutcome late = qa.rejected(job);

    assertThat(late).isInstanceOf(Answered.class);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.ANSWERED);
  }

  @Test
  void second_generation_of_an_answered_reading_keeps_the_first_answer() {
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("acc", id, QaKind.PAID, QaTopic.TinhDuyen, Q, "vi"));
    qa.generate(job);
    cache.entries.clear();
    provider.then(GOOD + " Thêm một câu khác nhé con.");

    QaOutcome again = qa.generate(job);

    assertThat(((Answered) again).text()).isEqualTo(GOOD);
  }

  @Test
  void free_failure_after_answer_keeps_answered() {
    UUID id = UUID.randomUUID();
    Prepared.Job job =
        (Prepared.Job)
            qa.prepare(new AskCommand("guest", id, QaKind.FREE, QaTopic.TinhDuyen, Q, "vi"));
    qa.generate(job);

    assertThat(qa.rejected(job)).isInstanceOf(Answered.class);
    assertThat(repo.reading(id).status()).isEqualTo(QaStatus.ANSWERED);
  }

  @Test
  void failed_follow_up_frees_its_seq_for_the_next_one() {
    UUID id = UUID.randomUUID();
    paid(id);
    Prepared.Job first =
        (Prepared.Job)
            qa.prepareFollowUp(
                new FollowUpCommand("acc", id, UUID.randomUUID(), "Vậy năm sau thì sao"));
    Prepared.Job second =
        (Prepared.Job)
            qa.prepareFollowUp(
                new FollowUpCommand("acc", id, UUID.randomUUID(), "Còn chuyện nhà thì sao"));
    provider.thenFail();
    assertThat(qa.generate(first)).isInstanceOf(Busy.class); // seq 1 deleted
    assertThat(((Answered) qa.generate(second)).seq()).isEqualTo(2);

    QaOutcome third = followUp(id, "Thêm một câu nữa được không");

    assertThat(((Answered) third).seq()).isEqualTo(1);
  }

  @Test
  void unknown_bearer_is_unauthenticated() {
    assertThatThrownBy(() -> qa.list("nope")).isInstanceOf(UnauthenticatedException.class);
  }
}
