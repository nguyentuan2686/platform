package dev.platform.reading.tuvi.adapter.config;

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
      Map.of("groq", "GROQ_API_KEY", "gemini", "GEMINI_API_KEY", "paid", "LLM_PAID_API_KEY");

  /** Providers in failover order. */
  record ConfiguredProviders(List<LlmProviderPort> ordered) {}

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

  @Bean
  LlmGateway llmGateway(
      ConfiguredProviders providers, LlmUsagePort usage, PaidPricing pricing, Clock clock) {
    return new LlmGateway(providers.ordered(), usage, pricing, clock);
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
    switch (id) {
      case "groq" -> {
        base = URI.create(env.getProperty("GROQ_BASE_URL", "https://api.groq.com/openai/v1"));
        model = env.getProperty("GROQ_MODEL", "openai/gpt-oss-120b");
        limit = Integer.parseInt(env.getProperty("GROQ_DAILY_REQUEST_LIMIT", "900"));
      }
      case "gemini" -> {
        base =
            URI.create(
                env.getProperty(
                    "GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai"));
        model = env.getProperty("GEMINI_MODEL", "gemini-3.5-flash-lite");
        limit = Integer.parseInt(env.getProperty("GEMINI_DAILY_REQUEST_LIMIT", "450"));
      }
      default -> {
        base = URI.create(env.getProperty("LLM_PAID_BASE_URL", "https://api.groq.com/openai/v1"));
        model = env.getProperty("LLM_PAID_MODEL", "openai/gpt-oss-120b");
        limit = Integer.MAX_VALUE;
        paid = true;
      }
    }
    Duration timeout = Duration.parse(env.getProperty("LLM_TIMEOUT", "PT20S").strip());
    return new OpenAiCompatibleLlmAdapter(
        new OpenAiCompatibleSettings(id, base, key, model, limit, paid, timeout),
        RestClient.builder());
  }

  private static BigDecimal decimal(Environment env, String name, String fallback) {
    return new BigDecimal(env.getProperty(name, fallback).strip());
  }
}
