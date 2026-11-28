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
  private static final Duration SHORT_RETRY = Duration.ofSeconds(60);
  private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000L);

  private final List<LlmProviderPort> ordered;
  private final LlmUsagePort usage;
  private final Map<String, PaidPricing> pricingById;
  private final PaidPricing defaultPricing;
  private final Clock clock;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public LlmGateway(
      List<LlmProviderPort> ordered, LlmUsagePort usage, PaidPricing pricing, Clock clock) {
    this(ordered, usage, Map.of(), pricing, clock);
  }

  /** {@code pricingById} overrides {@code defaultPricing} for the paid providers it names. */
  public LlmGateway(
      List<LlmProviderPort> ordered,
      LlmUsagePort usage,
      Map<String, PaidPricing> pricingById,
      PaidPricing defaultPricing,
      Clock clock) {
    this.ordered = List.copyOf(ordered);
    this.usage = usage;
    this.pricingById = Map.copyOf(pricingById);
    this.defaultPricing = defaultPricing;
    this.clock = clock;
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
        Instant until = rateLimitUntil(e, now, today);
        usage.markExhausted(provider.id(), until);
        LOG.warn("llm provider rate limited provider={} until={}", provider.id(), until);
      } catch (LlmUnavailableException e) {
        LOG.warn("llm provider unavailable provider={}", provider.id());
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

  private Instant rateLimitUntil(LlmRateLimitedException e, Instant now, LocalDate today) {
    return e.retryAfter()
        .filter(d -> d.compareTo(SHORT_RETRY) <= 0)
        .map(now::plus)
        .orElseGet(() -> today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  private void warnIfBudgetNear(LlmProviderPort provider, LocalDate today) {
    YearMonth month = YearMonth.from(today);
    if (usage.spendMicroUsd(provider.id(), month) * 5 >= budgetMicro(pricing(provider)) * 4
        && warned.add(provider.id() + "/" + month)) {
      LOG.warn("llm paid budget 80% reached provider={}", provider.id());
    }
  }
}
