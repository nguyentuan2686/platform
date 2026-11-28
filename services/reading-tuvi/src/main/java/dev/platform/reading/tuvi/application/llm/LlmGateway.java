package dev.platform.reading.tuvi.application.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks providers in order, honouring tier, exhaustion, daily quota and each paid provider's
 * monthly budget. State lives behind {@link LlmUsagePort} (read on every call) so replicas agree.
 */
public class LlmGateway {

  private static final Logger LOG = LoggerFactory.getLogger(LlmGateway.class);

  /** Backoff after a 429 without Retry-After, unless configured otherwise. */
  public static final Duration DEFAULT_RATE_LIMIT_BACKOFF = Duration.ofHours(1);

  private static final Duration MAX_BACKOFF = Duration.ofDays(1);
  private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000L);

  private final List<LlmProviderPort> ordered;
  private final LlmUsagePort usage;
  private final Map<String, PaidPricing> pricingById;
  private final PaidPricing defaultPricing;
  private final Clock clock;
  private final Duration rateLimitBackoff;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public LlmGateway(
      List<LlmProviderPort> ordered, LlmUsagePort usage, PaidPricing pricing, Clock clock) {
    this(ordered, usage, Map.of(), pricing, clock, DEFAULT_RATE_LIMIT_BACKOFF);
  }

  public LlmGateway(
      List<LlmProviderPort> ordered,
      LlmUsagePort usage,
      Map<String, PaidPricing> pricingById,
      PaidPricing defaultPricing,
      Clock clock) {
    this(ordered, usage, pricingById, defaultPricing, clock, DEFAULT_RATE_LIMIT_BACKOFF);
  }

  /**
   * {@code pricingById} overrides {@code defaultPricing} for the paid providers it names; {@code
   * rateLimitBackoff} is how long a provider is skipped after a 429 without Retry-After.
   */
  public LlmGateway(
      List<LlmProviderPort> ordered,
      LlmUsagePort usage,
      Map<String, PaidPricing> pricingById,
      PaidPricing defaultPricing,
      Clock clock,
      Duration rateLimitBackoff) {
    this.ordered = List.copyOf(ordered);
    this.usage = usage;
    this.pricingById = Map.copyOf(pricingById);
    this.defaultPricing = defaultPricing;
    this.clock = clock;
    this.rateLimitBackoff = rateLimitBackoff;
  }

  public LlmCompletion complete(LlmRequest request) {
    for (LlmProviderPort provider : ordered) {
      Instant now = clock.instant();
      LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
      if (!usable(provider, request, now, today)) {
        continue;
      }
      usage.recordAttempt(provider.id(), today);
      long started = System.nanoTime();
      try {
        LlmCompletion out = provider.complete(request);
        long cost = provider.paid() ? costMicroUsd(pricing(provider), out) : 0L;
        usage.recordTokens(provider.id(), today, out.promptTokens(), out.completionTokens(), cost);
        LOG.info(
            "llm completed provider={} latency_ms={} prompt_tokens={} completion_tokens={}"
                + " cache_write_tokens={} cache_read_tokens={}",
            provider.id(),
            (System.nanoTime() - started) / 1_000_000,
            out.promptTokens(),
            out.completionTokens(),
            out.cacheWriteTokens(),
            out.cacheReadTokens());
        if (provider.paid()) {
          warnIfBudgetNear(provider, today);
        }
        return out;
      } catch (LlmRateLimitedException e) {
        Instant until = rateLimitUntil(e, now);
        usage.markExhausted(provider.id(), until);
        LOG.warn("llm provider rate limited provider={} until={}", provider.id(), until);
      } catch (LlmUnavailableException e) {
        LOG.warn("llm provider unavailable provider={}", provider.id());
        // A refused or cut-off reply is still billed: count it toward the budget.
        e.billed()
            .ifPresent(
                b ->
                    usage.recordTokens(
                        provider.id(),
                        today,
                        b.promptTokens(),
                        b.completionTokens(),
                        provider.paid() ? costMicroUsd(pricing(provider), b) : 0L));
      }
    }
    throw new AllProvidersExhaustedException();
  }

  private boolean usable(LlmProviderPort p, LlmRequest request, Instant now, LocalDate today) {
    if (request.tier() == LlmTier.FREE_ONLY && !p.servesFreeTier()) {
      return false;
    }
    if (usage.exhaustedUntil(p.id()).map(until -> until.isAfter(now)).orElse(false)) {
      return false;
    }
    if (usage.requestsOn(p.id(), today) >= p.dailyRequestLimit()) {
      return false;
    }
    return !p.paid()
        || usage.spendMicroUsd(p.id(), YearMonth.from(today)) < budgetMicro(pricing(p));
  }

  private PaidPricing pricing(LlmProviderPort p) {
    return pricingById.getOrDefault(p.id(), defaultPricing);
  }

  private static long budgetMicro(PaidPricing pricing) {
    return pricing.monthlyBudgetUsd().multiply(MICRO).longValue();
  }

  /** USD per MTok times tokens is micro-USD; cache tokens are priced apart from plain input. */
  private static long costMicroUsd(PaidPricing pricing, LlmCompletion out) {
    long cached = (long) out.cacheWriteTokens() + out.cacheReadTokens();
    long plainInput = Math.max(0L, out.promptTokens() - cached);
    return pricing
        .inputUsdPerMTok()
        .multiply(BigDecimal.valueOf(plainInput))
        .add(pricing.cacheWriteUsdPerMTok().multiply(BigDecimal.valueOf(out.cacheWriteTokens())))
        .add(pricing.cacheReadUsdPerMTok().multiply(BigDecimal.valueOf(out.cacheReadTokens())))
        .add(pricing.outputUsdPerMTok().multiply(BigDecimal.valueOf(out.completionTokens())))
        .setScale(0, RoundingMode.CEILING)
        .longValueExact();
  }

  /**
   * Honours the provider's Retry-After (capped at a day); without one, backs off an hour. Not
   * "until UTC midnight": quotas reset on the provider's clock (Gemini: midnight Pacific), so a
   * midnight retry can hit 429 again and lose a whole extra day.
   */
  private Instant rateLimitUntil(LlmRateLimitedException e, Instant now) {
    Duration wait = e.retryAfter().orElse(rateLimitBackoff);
    return now.plus(wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait);
  }

  private void warnIfBudgetNear(LlmProviderPort provider, LocalDate today) {
    YearMonth month = YearMonth.from(today);
    if (usage.spendMicroUsd(provider.id(), month) * 5 >= budgetMicro(pricing(provider)) * 4
        && warned.add(provider.id() + "/" + month)) {
      LOG.warn("llm paid budget 80% reached provider={}", provider.id());
    }
  }
}
