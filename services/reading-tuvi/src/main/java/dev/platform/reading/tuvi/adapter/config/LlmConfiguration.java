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

  /**
   * One OpenAI-compatible provider: its env prefix ({@code <PREFIX>_API_KEY}, {@code _BASE_URL},
   * {@code _MODEL}, {@code _DAILY_REQUEST_LIMIT}, {@code _REASONING_EFFORT}, {@code _PAID}, {@code
   * _SERVE_FREE_TIER}) and the defaults of each. {@code servesFreeWhenPaid}: a paid model of this
   * provider still answers free questions, within its monthly budget, unless turned off.
   */
  private record OpenAiSpec(
      String prefix,
      String baseUrl,
      String model,
      int dailyRequestLimit,
      String reasoningEffort,
      boolean paid,
      boolean servesFreeWhenPaid) {}

  /**
   * Defaults probed with the prod keys on 2026-11-28 (chat completion 200, Vietnamese answer within
   * the FREE token budget): Gemini gemini-3.5-flash-lite via the OpenAI-compatible endpoint, Groq
   * openai/gpt-oss-120b (a reasoning model: its reasoning tokens count toward max_tokens; at the
   * default effort it used all 350 FREE tokens). The OpenRouter (google/gemma-4-31b-it:free, 50
   * free-model requests a day without credits) and Cerebras (gpt-oss-120b) defaults were not
   * probed: no prod key may be probed, so check the first prod answers in the logs. Model ids are
   * retired without notice: list them with GET {base}/models and override with {@code
   * <PREFIX>_MODEL}. The paid defaults (LLM_PAID_*) were priced for the old Groq model; re-check
   * https://groq.com/pricing before enabling paid.
   */
  private static final Map<String, OpenAiSpec> OPENAI =
      Map.of(
          "groq",
          new OpenAiSpec(
              "GROQ",
              "https://api.groq.com/openai/v1",
              "openai/gpt-oss-120b",
              900,
              "low",
              false,
              false),
          "gemini",
          new OpenAiSpec(
              "GEMINI",
              "https://generativelanguage.googleapis.com/v1beta/openai",
              "gemini-3.5-flash-lite",
              450,
              "",
              false,
              false),
          "openrouter",
          new OpenAiSpec(
              "OPENROUTER",
              "https://openrouter.ai/api/v1",
              "google/gemma-4-31b-it:free",
              45,
              "",
              false,
              true),
          "cerebras",
          new OpenAiSpec(
              "CEREBRAS", "https://api.cerebras.ai/v1", "gpt-oss-120b", 900, "low", false, false),
          "paid",
          new OpenAiSpec(
              "LLM_PAID",
              "https://api.groq.com/openai/v1",
              "openai/gpt-oss-120b",
              Integer.MAX_VALUE,
              "low",
              true,
              false));

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
      } else if (id.equals("claude") || OPENAI.containsKey(id)) {
        String keyVar =
            id.equals("claude") ? "ANTHROPIC_API_KEY" : OPENAI.get(id).prefix() + "_API_KEY";
        String key = env.getProperty(keyVar, "").strip();
        if (key.isBlank()) {
          LOG.warn("llm provider {} disabled: no api key", id);
        } else if (id.equals("claude")) {
          providers.add(anthropic(env, key));
        } else {
          providers.add(openAi(env, id, OPENAI.get(id), key));
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
   *
   * <p>OpenRouter is priced only when {@code OPENROUTER_PAID=true} (its default model is free). The
   * defaults are deliberately high, so that a paid model whose prices nobody set reaches its budget
   * early rather than late: set {@code OPENROUTER_*_USD_PER_MTOK} to the model's prices on
   * https://openrouter.ai/models when switching (e.g. to deepseek/deepseek-v4-flash).
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
                decimal(env, "ANTHROPIC_MONTHLY_BUDGET_USD", "5")),
            "openrouter",
            new PaidPricing(
                decimal(env, "OPENROUTER_INPUT_USD_PER_MTOK", "1.00"),
                decimal(env, "OPENROUTER_OUTPUT_USD_PER_MTOK", "4.00"),
                decimal(env, "OPENROUTER_MONTHLY_BUDGET_USD", "2"))));
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
   * A paid model has no daily cap by default (its monthly budget bounds it); a free one keeps the
   * provider's free daily quota minus a margin.
   */
  private static LlmProviderPort openAi(Environment env, String id, OpenAiSpec spec, String key) {
    String p = spec.prefix();
    boolean paid =
        Boolean.parseBoolean(env.getProperty(p + "_PAID", String.valueOf(spec.paid())).strip());
    int defaultLimit = paid ? Integer.MAX_VALUE : spec.dailyRequestLimit();
    boolean servesFree =
        Boolean.parseBoolean(
            env.getProperty(
                    p + "_SERVE_FREE_TIER", String.valueOf(!paid || spec.servesFreeWhenPaid()))
                .strip());
    Duration timeout = Duration.parse(env.getProperty("LLM_TIMEOUT", "PT20S").strip());
    return new OpenAiCompatibleLlmAdapter(
        new OpenAiCompatibleSettings(
            id,
            URI.create(env.getProperty(p + "_BASE_URL", spec.baseUrl()).strip()),
            key,
            env.getProperty(p + "_MODEL", spec.model()).strip(),
            Integer.parseInt(
                env.getProperty(p + "_DAILY_REQUEST_LIMIT", String.valueOf(defaultLimit)).strip()),
            paid,
            timeout,
            env.getProperty(p + "_REASONING_EFFORT", spec.reasoningEffort()).strip(),
            servesFree),
        RestClient.builder());
  }

  private static BigDecimal decimal(Environment env, String name, String fallback) {
    return new BigDecimal(env.getProperty(name, fallback).strip());
  }
}
