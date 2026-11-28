package dev.platform.reading.tuvi.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmUsagePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LlmConfigurationTest {

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(LlmConfiguration.class)
          .withBean(LlmUsagePort.class, () -> Mockito.mock(LlmUsagePort.class));

  static List<String> ids(LlmConfiguration.ConfiguredProviders p) {
    return p.ordered().stream().map(LlmProviderPort::id).toList();
  }

  @Test
  void default_is_exactly_fake() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class)))
              .containsExactly("fake");
        });
  }

  @Test
  void groq_without_key_is_skipped_with_warn(CapturedOutput output) {
    runner
        .withPropertyValues("LLM_PROVIDERS=groq,fake")
        .run(
            ctx -> {
              assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class)))
                  .containsExactly("fake");
              assertThat(output.getAll()).contains("llm provider groq disabled: no api key");
            });
  }

  @Test
  void unknown_provider_fails_startup() {
    runner
        .withPropertyValues("LLM_PROVIDERS=bogus")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unknown llm provider bogus"));
  }

  @Test
  void zero_providers_left_still_starts_with_an_error_log(CapturedOutput output) {
    runner
        .withPropertyValues("LLM_PROVIDERS=groq,gemini")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class))).isEmpty();
              assertThat(ctx).hasSingleBean(LlmGateway.class);
              assertThat(output.getAll()).contains("no llm provider enabled");
            });
  }

  @Test
  void keyed_providers_are_enabled_and_keyless_skipped() {
    runner
        .withPropertyValues("LLM_PROVIDERS=groq,gemini,fake", "GROQ_API_KEY=local-test-groq-key")
        .run(
            ctx -> {
              var providers = ctx.getBean(LlmConfiguration.ConfiguredProviders.class);
              assertThat(ids(providers)).containsExactly("groq", "fake");
              assertThat(providers.ordered().get(0).dailyRequestLimit()).isEqualTo(900);
            });
  }

  @Test
  void paid_provider_is_flagged_paid() {
    runner
        .withPropertyValues("LLM_PROVIDERS=paid,fake", "LLM_PAID_API_KEY=local-test-paid-key")
        .run(
            ctx -> {
              var providers = ctx.getBean(LlmConfiguration.ConfiguredProviders.class);
              assertThat(ids(providers)).containsExactly("paid", "fake");
              assertThat(providers.ordered().get(0).paid()).isTrue();
            });
  }

  @Test
  void claude_with_key_comes_first_and_serves_free_tier_by_default() {
    runner
        .withPropertyValues(
            "LLM_PROVIDERS=claude,groq,fake",
            "ANTHROPIC_API_KEY=local-test-anthropic-key",
            "GROQ_API_KEY=local-test-groq-key")
        .run(
            ctx -> {
              var providers = ctx.getBean(LlmConfiguration.ConfiguredProviders.class);
              assertThat(ids(providers)).containsExactly("claude", "groq", "fake");
              var claude = providers.ordered().get(0);
              assertThat(claude.paid()).isTrue();
              assertThat(claude.servesFreeTier()).isTrue();
              assertThat(claude.dailyRequestLimit()).isEqualTo(Integer.MAX_VALUE);
            });
  }

  @Test
  void claude_without_key_is_skipped_with_warn(CapturedOutput output) {
    runner
        .withPropertyValues("LLM_PROVIDERS=claude,fake")
        .run(
            ctx -> {
              assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class)))
                  .containsExactly("fake");
              assertThat(output.getAll()).contains("llm provider claude disabled: no api key");
            });
  }

  @Test
  void claude_free_tier_can_be_turned_off() {
    runner
        .withPropertyValues(
            "LLM_PROVIDERS=claude",
            "ANTHROPIC_API_KEY=local-test-anthropic-key",
            "ANTHROPIC_SERVE_FREE_TIER=false")
        .run(
            ctx ->
                assertThat(
                        ctx.getBean(LlmConfiguration.ConfiguredProviders.class)
                            .ordered()
                            .get(0)
                            .servesFreeTier())
                    .isFalse());
  }

  @Test
  void claude_pricing_defaults_and_overrides() {
    runner
        .withPropertyValues("ANTHROPIC_MONTHLY_BUDGET_USD=3")
        .run(
            ctx -> {
              var pricing = ctx.getBean(LlmConfiguration.ProviderPricing.class).byId();
              var claude = pricing.get("claude");
              assertThat(claude.inputUsdPerMTok()).isEqualByComparingTo("0.10");
              assertThat(claude.outputUsdPerMTok()).isEqualByComparingTo("0.50");
              assertThat(claude.cacheWriteUsdPerMTok()).isEqualByComparingTo("0.125");
              assertThat(claude.cacheReadUsdPerMTok()).isEqualByComparingTo("0.01");
              assertThat(claude.monthlyBudgetUsd()).isEqualByComparingTo("3");
            });
  }

  @Test
  void openrouter_and_cerebras_without_keys_are_skipped_with_warn(CapturedOutput output) {
    runner
        .withPropertyValues("LLM_PROVIDERS=openrouter,cerebras,fake")
        .run(
            ctx -> {
              assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class)))
                  .containsExactly("fake");
              assertThat(output.getAll())
                  .contains("llm provider openrouter disabled: no api key")
                  .contains("llm provider cerebras disabled: no api key");
            });
  }

  @Test
  void openrouter_and_cerebras_with_keys_are_free_by_default() {
    runner
        .withPropertyValues(
            "LLM_PROVIDERS=claude,gemini,groq,openrouter,cerebras",
            "OPENROUTER_API_KEY=local-test-openrouter-key",
            "CEREBRAS_API_KEY=local-test-cerebras-key")
        .run(
            ctx -> {
              var providers = ctx.getBean(LlmConfiguration.ConfiguredProviders.class).ordered();
              assertThat(providers.stream().map(LlmProviderPort::id))
                  .containsExactly("openrouter", "cerebras");
              assertThat(providers)
                  .allSatisfy(
                      p -> {
                        assertThat(p.paid()).isFalse();
                        assertThat(p.servesFreeTier()).isTrue();
                      });
              assertThat(providers.get(0).dailyRequestLimit()).isEqualTo(45);
              assertThat(providers.get(1).dailyRequestLimit()).isEqualTo(900);
            });
  }

  @Test
  void openrouter_paid_model_is_env_only_priced_and_still_serves_free_tier() {
    runner
        .withPropertyValues(
            "LLM_PROVIDERS=openrouter",
            "OPENROUTER_API_KEY=local-test-openrouter-key",
            "OPENROUTER_MODEL=deepseek/deepseek-v4-flash",
            "OPENROUTER_PAID=true",
            "OPENROUTER_INPUT_USD_PER_MTOK=0.2",
            "OPENROUTER_OUTPUT_USD_PER_MTOK=0.8",
            "OPENROUTER_MONTHLY_BUDGET_USD=2")
        .run(
            ctx -> {
              var openrouter =
                  ctx.getBean(LlmConfiguration.ConfiguredProviders.class).ordered().get(0);
              assertThat(openrouter.paid()).isTrue();
              assertThat(openrouter.servesFreeTier()).isTrue();
              assertThat(openrouter.dailyRequestLimit()).isEqualTo(Integer.MAX_VALUE);
              var pricing = ctx.getBean(LlmConfiguration.ProviderPricing.class).byId();
              assertThat(pricing.get("openrouter").inputUsdPerMTok()).isEqualByComparingTo("0.2");
              assertThat(pricing.get("openrouter").outputUsdPerMTok()).isEqualByComparingTo("0.8");
              assertThat(pricing.get("openrouter").monthlyBudgetUsd()).isEqualByComparingTo("2");
            });
  }

  @Test
  void openrouter_paid_can_stay_off_the_free_tier() {
    runner
        .withPropertyValues(
            "LLM_PROVIDERS=openrouter",
            "OPENROUTER_API_KEY=local-test-openrouter-key",
            "OPENROUTER_PAID=true",
            "OPENROUTER_SERVE_FREE_TIER=false")
        .run(
            ctx ->
                assertThat(
                        ctx.getBean(LlmConfiguration.ConfiguredProviders.class)
                            .ordered()
                            .get(0)
                            .servesFreeTier())
                    .isFalse());
  }

  @Test
  void paid_provider_stays_off_the_free_tier() {
    runner
        .withPropertyValues("LLM_PROVIDERS=paid", "LLM_PAID_API_KEY=local-test-paid-key")
        .run(
            ctx ->
                assertThat(
                        ctx.getBean(LlmConfiguration.ConfiguredProviders.class)
                            .ordered()
                            .get(0)
                            .servesFreeTier())
                    .isFalse());
  }

  @Test
  void bad_rate_limit_backoff_fails_startup() {
    runner.withPropertyValues("LLM_RATE_LIMIT_BACKOFF=1h").run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void rate_limit_backoff_is_configurable() {
    runner
        .withPropertyValues("LLM_RATE_LIMIT_BACKOFF=PT15M")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(LlmGateway.class));
  }

  @Test
  void gateway_bean_is_built() {
    runner.run(ctx -> assertThat(ctx).hasSingleBean(LlmGateway.class));
  }

  @Test
  void whitespace_only_key_counts_as_missing() {
    runner
        .withPropertyValues("LLM_PROVIDERS=groq,fake", "GROQ_API_KEY= \n")
        .run(
            ctx ->
                assertThat(ids(ctx.getBean(LlmConfiguration.ConfiguredProviders.class)))
                    .containsExactly("fake"));
  }
}
