package dev.platform.reading.tuvi.adapter.config;

import dev.platform.reading.tuvi.adapter.out.llm.AnthropicLlmAdapter;
import dev.platform.reading.tuvi.adapter.out.llm.AnthropicSettings;
import dev.platform.reading.tuvi.adapter.out.llm.FakeLlmProvider;
import dev.platform.reading.tuvi.adapter.out.llm.FakeMode;
import dev.platform.reading.tuvi.adapter.out.llm.OpenAiCompatibleLlmAdapter;
import dev.platform.reading.tuvi.adapter.out.llm.OpenAiCompatibleSettings;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmUsagePort;
import dev.platform.reading.tuvi.application.llm.PaidPricing;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/** Builds the ordered LLM provider list from {@code LLM_PROVIDERS} (default {@code fake}). */
@Configuration(proxyBeanMethods = false)
class LlmConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(LlmConfiguration.class);

  /** Env var holding each keyed provider's API key. */
  private static final Map<String, String> KEY_VARS =
      Map.of(
          "claude", "ANTHROPIC_API_KEY",
          "groq", "GROQ_API_KEY",
          "gemini", "GEMINI_API_KEY",
          "paid", "LLM_PAID_API_KEY");

  /** Providers in failover order. */
  record ConfiguredProviders(List<LlmProviderPort> ordered) {}

  /** Paid providers priced apart from the {@code paid} defaults, by provider id. */
  record ProviderPricing(Map<String, PaidPricing> byId) {}

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ConfiguredProviders configuredProviders(Environment env) {
    List<LlmProviderPort> providers = new ArrayList<>();
    for (String raw : env.getProperty("LLM_PROVIDERS", "fake").split(",")) {
      String id = raw.strip();
      if (id.isEmpty()) {
        continue;
      }
      if (id.equals("fake")) {
        String mode = env.getProperty("LLM_FAKE_MODE", "ok").strip().toUpperCase(Locale.ROOT);
        providers.add(new FakeLlmProvider(FakeMode.valueOf(mode)));
      } else if (KEY_VARS.containsKey(id)) {
        String key = env.getProperty(KEY_VARS.get(id), "").strip();
        if (key.isBlank()) {
          LOG.warn("llm provider {} disabled: no api key", id);
        } else if (id.equals("claude")) {
          providers.add(anthropic(env, key));
        } else {
          providers.add(openAi(env, id, key));
        }
      } else {
        throw new IllegalStateException("unknown llm provider " + id);
      }
    }
    if (providers.isEmpty()) {
      // Readings and charts do not need an LLM: start anyway, Q&A answers fail as "all providers
      // exhausted" until a key is set (a missing key once crash-looped the whole service in prod).
      LOG.error("no llm provider enabled (check LLM_PROVIDERS and the api keys): Q&A unavailable");
    }
    return new ConfiguredProviders(List.copyOf(providers));
  }

  @Bean
  PaidPricing paidPricing(Environment env) {
    return new PaidPricing(
        decimal(env, "LLM_PAID_INPUT_USD_PER_MTOK", "0.59"),
        decimal(env, "LLM_PAID_OUTPUT_USD_PER_MTOK", "0.79"),
        decimal(env, "LLM_PAID_MONTHLY_BUDGET_USD", "5"));
  }

  /**
   * Claude defaults: claude-haiku-5-5 list prices for prompts up to 100K tokens ($0.10 in, $0.50
   * out, 5-minute cache write 1.25x and cache read 0.1x of input), checked against the claude-api
   * skill on 2026-11-28. Re-check https://platform.claude.com/docs/en/about-claude/pricing when
   * changing the model.
   */
  @Bean
  ProviderPricing providerPricing(Environment env) {
    return new ProviderPricing(
        Map.of(
            "claude",
            new PaidPricing(
                decimal(env, "ANTHROPIC_INPUT_USD_PER_MTOK", "0.10"),
                decimal(env, "ANTHROPIC_OUTPUT_USD_PER_MTOK", "0.50"),
                decimal(env, "ANTHROPIC_CACHE_WRITE_USD_PER_MTOK", "0.125"),
                decimal(env, "ANTHROPIC_CACHE_READ_USD_PER_MTOK", "0.01"),
                decimal(env, "ANTHROPIC_MONTHLY_BUDGET_USD", "5"))));
  }

  @Bean
  LlmGateway llmGateway(
      ConfiguredProviders providers,
      LlmUsagePort usage,
      ProviderPricing providerPricing,
      PaidPricing pricing,
      Clock clock,
      Environment env) {
    // a 429 without Retry-After skips the provider this long (quotas reset on the provider's clock)
    Duration backoff = Duration.parse(env.getProperty("LLM_RATE_LIMIT_BACKOFF", "PT1H").strip());
    return new LlmGateway(
        providers.ordered(), usage, providerPricing.byId(), pricing, clock, backoff);
  }

  /**
   * claude-haiku-5-5: the low-cost tier, good instruction following, and a Q&A prompt is ~2K
   * tokens. Effort {@code low} keeps adaptive thinking short; the headroom keeps thinking from
   * eating the answer's token budget.
   */
  private static LlmProviderPort anthropic(Environment env, String key) {
    return new AnthropicLlmAdapter(
        new AnthropicSettings(
            "claude",
            URI.create(env.getProperty("ANTHROPIC_BASE_URL", "https://api.anthropic.com").strip()),
            key,
            env.getProperty("ANTHROPIC_MODEL", "claude-haiku-5-5").strip(),
            env.getProperty("ANTHROPIC_EFFORT", "low").strip(),
            Integer.parseInt(env.getProperty("ANTHROPIC_THINKING_HEADROOM_TOKENS", "1024").strip()),
            Boolean.parseBoolean(env.getProperty("ANTHROPIC_PROMPT_CACHE", "true").strip()),
            Integer.parseInt(
                env.getProperty("ANTHROPIC_DAILY_REQUEST_LIMIT", String.valueOf(Integer.MAX_VALUE))
                    .strip()),
            Boolean.parseBoolean(env.getProperty("ANTHROPIC_SERVE_FREE_TIER", "true").strip()),
            Duration.parse(env.getProperty("ANTHROPIC_TIMEOUT", "PT30S").strip())),
        RestClient.builder());
  }

  /**
   * Defaults probed with the prod keys on 2026-11-28 (chat completion 200, Vietnamese answer within
   * the FREE token budget): Gemini gemini-3.5-flash-lite via the OpenAI-compatible endpoint, Groq
   * openai/gpt-oss-120b (a reasoning model: its reasoning tokens count toward max_tokens). The
   * earlier defaults gemini-2.5-flash-lite and llama-3.3-70b-versatile were retired (HTTP 404).
   * Model ids are retired without notice: list them with GET {base}/models and override with
   * GEMINI_MODEL / GROQ_MODEL. The paid defaults (LLM_PAID_*) were priced for the old Groq model;
   * re-check https://groq.com/pricing before enabling paid.
   */
  private static LlmProviderPort openAi(Environment env, String id, String key) {
    URI base;
    String model;
    int limit;
    boolean paid = false;
    String reasoning;
    switch (id) {
      case "groq" -> {
        base = URI.create(env.getProperty("GROQ_BASE_URL", "https://api.groq.com/openai/v1"));
        model = env.getProperty("GROQ_MODEL", "openai/gpt-oss-120b");
        limit = Integer.parseInt(env.getProperty("GROQ_DAILY_REQUEST_LIMIT", "900"));
        // gpt-oss reasons inside max_tokens: at the default effort it used all 350 FREE tokens
        reasoning = env.getProperty("GROQ_REASONING_EFFORT", "low");
      }
      case "gemini" -> {
        base =
            URI.create(
                env.getProperty(
                    "GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai"));
        model = env.getProperty("GEMINI_MODEL", "gemini-3.5-flash-lite");
        limit = Integer.parseInt(env.getProperty("GEMINI_DAILY_REQUEST_LIMIT", "450"));
        reasoning = env.getProperty("GEMINI_REASONING_EFFORT", "");
      }
      default -> {
        base = URI.create(env.getProperty("LLM_PAID_BASE_URL", "https://api.groq.com/openai/v1"));
        model = env.getProperty("LLM_PAID_MODEL", "openai/gpt-oss-120b");
        limit = Integer.MAX_VALUE;
        reasoning = env.getProperty("LLM_PAID_REASONING_EFFORT", "low");
        paid = true;
      }
    }
    Duration timeout = Duration.parse(env.getProperty("LLM_TIMEOUT", "PT20S").strip());
    return new OpenAiCompatibleLlmAdapter(
        new OpenAiCompatibleSettings(id, base, key, model, limit, paid, timeout, reasoning),
        RestClient.builder());
  }

  private static BigDecimal decimal(Environment env, String name, String fallback) {
    return new BigDecimal(env.getProperty(name, fallback).strip());
  }
}
