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
  void zero_providers_left_fails_startup() {
    runner.withPropertyValues("LLM_PROVIDERS=groq").run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void gateway_bean_is_built() {
    runner.run(ctx -> assertThat(ctx).hasSingleBean(LlmGateway.class));
  }
}
