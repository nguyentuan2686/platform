package dev.platform.reading.tuvi.adapter.config;

import dev.platform.reading.tuvi.adapter.out.llm.FakeLlmProvider;
import dev.platform.reading.tuvi.adapter.out.llm.FakeMode;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmUsagePort;
import dev.platform.reading.tuvi.application.llm.PaidPricing;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
        String key = env.getProperty(KEY_VARS.get(id), "");
        if (key.isBlank()) {
          LOG.warn("llm provider {} disabled: no api key", id);
        } else {
          // Real HTTP adapters arrive in the next task; until then the id resolves to disabled.
          LOG.warn("llm provider {} disabled: adapter not available yet", id);
        }
      } else {
        throw new IllegalStateException("unknown llm provider " + id);
      }
    }
    if (providers.isEmpty()) {
      throw new IllegalStateException("no llm provider enabled (check LLM_PROVIDERS)");
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

  private static BigDecimal decimal(Environment env, String name, String fallback) {
    return new BigDecimal(env.getProperty(name, fallback).strip());
  }
}
