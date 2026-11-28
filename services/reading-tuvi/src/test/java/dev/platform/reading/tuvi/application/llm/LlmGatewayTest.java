package dev.platform.reading.tuvi.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LlmGatewayTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final PaidPricing PRICING =
      new PaidPricing(new BigDecimal("0.59"), new BigDecimal("0.79"), new BigDecimal("5"));
  static final LlmRequest REQ =
      new LlmRequest(List.of(new LlmMessage(LlmMessage.Role.USER, "hi")), 100, 0.3, LlmTier.ANY);

  /** Mutable clock. */
  static final class TestClock extends Clock {
    Instant now = T0;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static final class MemUsage implements LlmUsagePort {
    final Map<String, Integer> requests = new HashMap<>();
    final Map<String, Long> cost = new HashMap<>();
    final Map<String, Instant> exhausted = new HashMap<>();

    @Override
    public int requestsOn(String p, LocalDate d) {
      return requests.getOrDefault(p + d, 0);
    }

    @Override
    public void recordAttempt(String p, LocalDate d) {
      requests.merge(p + d, 1, Integer::sum);
    }

    @Override
    public void recordTokens(String p, LocalDate d, int pr, int co, long micro) {
      cost.merge(p + YearMonth.from(d), micro, Long::sum);
    }

    @Override
    public long spendMicroUsd(String p, YearMonth m) {
      return cost.getOrDefault(p + m, 0L);
    }

    @Override
    public void markExhausted(String p, Instant until) {
      exhausted.merge(p, until, (a, b) -> a.isAfter(b) ? a : b);
    }

    @Override
    public Optional<Instant> exhaustedUntil(String p) {
      return Optional.ofNullable(exhausted.get(p));
    }
  }

  static class StubProvider implements LlmProviderPort {
    final String id;
    final boolean paid;
    final int limit;
    final List<Object> script = new ArrayList<>(); // RuntimeException or LlmCompletion
    int calls;
    int promptTokens = 10;
    int completionTokens = 5;

    StubProvider(String id, boolean paid, int limit) {
      this.id = id;
      this.paid = paid;
      this.limit = limit;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean paid() {
      return paid;
    }

    @Override
    public int dailyRequestLimit() {
      return limit;
    }

    @Override
    public LlmCompletion complete(LlmRequest request) {
      calls++;
      if (!script.isEmpty()) {
        Object next = script.size() > 1 ? script.remove(0) : script.get(0);
        if (next instanceof RuntimeException e) {
          throw e;
        }
      }
      return new LlmCompletion("from-" + id, id, promptTokens, completionTokens);
    }
  }

  TestClock clock;
  MemUsage usage;

  @BeforeEach
  void setUp() {
    clock = new TestClock();
    usage = new MemUsage();
  }

  LlmGateway gateway(LlmProviderPort... providers) {
    return new LlmGateway(List.of(providers), usage, PRICING, clock);
  }

  @Test
  void first_provider_answers() {
    var a = new StubProvider("a", false, 100);
    var b = new StubProvider("b", false, 100);

    var out = gateway(a, b).complete(REQ);

    assertThat(out.text()).isEqualTo("from-a");
    assertThat(b.calls).isZero();
  }

  @Test
  void unavailable_falls_through_to_next() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmUnavailableException("boom"));
    var b = new StubProvider("b", false, 100);

    var out = gateway(a, b).complete(REQ);

    assertThat(out.provider()).isEqualTo("b");
    assertThat(usage.exhaustedUntil("a")).isEmpty();
  }

  @Test
  void rate_limit_with_retry_after_is_short() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmRateLimitedException(Optional.of(Duration.ofSeconds(20))));
    a.script.add(new LlmCompletion("x", "a", 1, 1));
    var b = new StubProvider("b", false, 100);
    var gw = gateway(a, b);

    assertThat(gw.complete(REQ).provider()).isEqualTo("b");
    assertThat(usage.exhaustedUntil("a")).contains(T0.plusSeconds(20));

    clock.now = T0.plusSeconds(10);
    assertThat(gw.complete(REQ).provider()).isEqualTo("b");
    assertThat(a.calls).isEqualTo(1);

    clock.now = T0.plusSeconds(21);
    assertThat(gw.complete(REQ).provider()).isEqualTo("a");
  }

  /**
   * A 429 without Retry-After backs off one hour, not until UTC midnight: Gemini's free quota
   * resets at midnight Pacific (08:00 UTC), so a midnight retry hit 429 again and skipped Gemini
   * for a whole extra day (prod, 2026-11-28).
   */
  @Test
  void rate_limit_without_retry_after_backs_off_one_hour() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmRateLimitedException(Optional.empty()));
    var b = new StubProvider("b", false, 100);

    gateway(a, b).complete(REQ);

    assertThat(usage.exhaustedUntil("a")).contains(T0.plus(Duration.ofHours(1)));
  }

  @Test
  void configured_backoff_applies_without_retry_after() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmRateLimitedException(Optional.empty()));
    var b = new StubProvider("b", false, 100);
    var gw = new LlmGateway(List.of(a, b), usage, Map.of(), PRICING, clock, Duration.ofMinutes(15));

    gw.complete(REQ);

    assertThat(usage.exhaustedUntil("a")).contains(T0.plus(Duration.ofMinutes(15)));
  }

  @Test
  void long_retry_after_is_honoured() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmRateLimitedException(Optional.of(Duration.ofMinutes(90))));
    var b = new StubProvider("b", false, 100);

    gateway(a, b).complete(REQ);

    assertThat(usage.exhaustedUntil("a")).contains(T0.plus(Duration.ofMinutes(90)));
  }

  @Test
  void absurd_retry_after_is_capped_at_one_day() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmRateLimitedException(Optional.of(Duration.ofDays(30))));
    var b = new StubProvider("b", false, 100);

    gateway(a, b).complete(REQ);

    assertThat(usage.exhaustedUntil("a")).contains(T0.plus(Duration.ofDays(1)));
  }

  @Test
  void daily_limit_reached_is_skipped() {
    var a = new StubProvider("a", false, 1);
    var b = new StubProvider("b", false, 100);
    var gw = gateway(a, b);

    assertThat(gw.complete(REQ).provider()).isEqualTo("a");
    assertThat(gw.complete(REQ).provider()).isEqualTo("b");
    assertThat(a.calls).isEqualTo(1);
  }

  @Test
  void free_tier_never_uses_paid() {
    var paid = new StubProvider("paid", true, 100);
    var gw = gateway(paid);
    var req = new LlmRequest(REQ.messages(), 100, 0.3, LlmTier.FREE_ONLY);

    assertThatThrownBy(() -> gw.complete(req)).isInstanceOf(AllProvidersExhaustedException.class);
    assertThat(paid.calls).isZero();
  }

  @Test
  void paid_budget_exhausted_skips_paid() {
    var paid = new StubProvider("paid", true, 100);
    usage.recordTokens("paid", LocalDate.of(2026, 11, 3), 0, 0, 5_000_000L);
    var gw = gateway(paid);

    assertThatThrownBy(() -> gw.complete(REQ)).isInstanceOf(AllProvidersExhaustedException.class);
    assertThat(paid.calls).isZero();
  }

  @Test
  void paid_cost_recorded_in_micro_usd() {
    var paid = new StubProvider("paid", true, 100);
    paid.promptTokens = 1_000_000;
    paid.completionTokens = 0;

    gateway(paid).complete(REQ);

    assertThat(usage.spendMicroUsd("paid", YearMonth.of(2026, 11))).isEqualTo(590_000L);
  }

  @Test
  void provider_specific_pricing_and_cache_tokens_are_costed() {
    var claude =
        new StubProvider("claude", true, 100) {
          @Override
          public LlmCompletion complete(LlmRequest request) {
            calls++;
            // 1M total input: 600K uncached, 300K cache write, 100K cache read; 1M output.
            return new LlmCompletion("ok", "claude", 1_000_000, 1_000_000, 300_000, 100_000);
          }
        };
    var claudePricing =
        new PaidPricing(
            new BigDecimal("0.10"),
            new BigDecimal("0.50"),
            new BigDecimal("0.125"),
            new BigDecimal("0.01"),
            new BigDecimal("2"));
    var gw =
        new LlmGateway(List.of(claude), usage, Map.of("claude", claudePricing), PRICING, clock);

    gw.complete(REQ);

    // 0.6*0.10 + 0.3*0.125 + 0.1*0.01 + 1*0.50 = 0.5985 USD
    assertThat(usage.spendMicroUsd("claude", YearMonth.of(2026, 11))).isEqualTo(598_500L);
  }

  @Test
  void billed_unusable_reply_is_costed_then_next_provider_answers() {
    var claude = new StubProvider("claude", true, 100);
    claude.script.add(
        new LlmUnavailableException(
            "cut off", new LlmCompletion("", "claude", 1_000_000, 1_000_000)));
    var groq = new StubProvider("groq", false, 100);
    var claudePricing =
        new PaidPricing(new BigDecimal("0.10"), new BigDecimal("0.50"), new BigDecimal("2"));
    var gw =
        new LlmGateway(
            List.of(claude, groq), usage, Map.of("claude", claudePricing), PRICING, clock);

    var out = gw.complete(REQ);

    assertThat(out.provider()).isEqualTo("groq");
    assertThat(usage.spendMicroUsd("claude", YearMonth.of(2026, 11))).isEqualTo(600_000L);
  }

  @Test
  void provider_budget_exhausted_falls_back_to_free() {
    var claude = new StubProvider("claude", true, 100);
    var groq = new StubProvider("groq", false, 100);
    var claudePricing =
        new PaidPricing(new BigDecimal("0.10"), new BigDecimal("0.50"), new BigDecimal("2"));
    usage.recordTokens("claude", LocalDate.of(2026, 11, 3), 0, 0, 2_000_000L);
    var gw =
        new LlmGateway(
            List.of(claude, groq), usage, Map.of("claude", claudePricing), PRICING, clock);

    var out = gw.complete(REQ);

    assertThat(out.provider()).isEqualTo("groq");
    assertThat(claude.calls).isZero();
  }

  @Test
  void paid_provider_serving_free_tier_is_used_for_free_questions() {
    var claude =
        new StubProvider("claude", true, 100) {
          @Override
          public boolean servesFreeTier() {
            return true;
          }
        };
    var req = new LlmRequest(REQ.messages(), 100, 0.3, LlmTier.FREE_ONLY);

    var out = gateway(claude).complete(req);

    assertThat(out.provider()).isEqualTo("claude");
  }

  @Test
  void free_provider_cost_is_zero() {
    var a = new StubProvider("a", false, 100);
    a.promptTokens = 1_000_000;

    gateway(a).complete(REQ);

    assertThat(usage.spendMicroUsd("a", YearMonth.of(2026, 11))).isZero();
  }

  @Test
  void budget_80_percent_warns_once(CapturedOutput output) {
    var paid = new StubProvider("paid", true, 100);
    paid.promptTokens = 5_000_000; // 2.95 USD per call, 59% of 5
    var gw = gateway(paid);

    gw.complete(REQ); // 59%: no warn
    gw.complete(REQ); // crosses 80%
    usage.cost.put("paid" + YearMonth.of(2026, 11), 0L);
    gw.complete(REQ);
    gw.complete(REQ); // crosses again, but already warned this month

    assertThat(output.getAll().split("llm paid budget 80% reached", -1)).hasSize(2);
  }

  @Test
  void every_attempt_counted() {
    var a = new StubProvider("a", false, 100);
    a.script.add(new LlmUnavailableException("boom"));
    a.script.add(new LlmCompletion("x", "a", 1, 1));
    var gw = gateway(a);

    assertThatThrownBy(() -> gw.complete(REQ)).isInstanceOf(AllProvidersExhaustedException.class);
    gw.complete(REQ);

    assertThat(usage.requestsOn("a", LocalDate.of(2026, 11, 25))).isEqualTo(2);
  }
}
