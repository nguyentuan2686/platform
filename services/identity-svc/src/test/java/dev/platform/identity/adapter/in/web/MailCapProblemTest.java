package dev.platform.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.MailCapReachedException;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The global daily mail cap: 429 Problem Details and the counter the mail-cap-reached alert reads.
 */
@WebMvcTest(AuthController.class)
@Import({
  PlatformWebAutoConfiguration.class,
  IdentityExceptionHandler.class,
  MailCapProblemTest.Metrics.class
})
class MailCapProblemTest {

  static final InMemoryMetricReader READER = InMemoryMetricReader.create();

  @TestConfiguration
  static class Metrics {
    @Bean
    OpenTelemetry openTelemetry() {
      return OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(READER).build())
          .build();
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean GuestUseCase guests;
  @MockitoBean MagicLinkUseCase magicLinks;
  @MockitoBean SessionUseCase sessions;

  private long capCounter() {
    return READER.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("identity.magic_link.daily_cap_reached"))
        .map(MetricData::getLongSumData)
        .flatMap(d -> d.getPoints().stream())
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  @Test
  void cap_reached_is_429_problem_and_counted() throws Exception {
    long before = capCounter();
    doThrow(new MailCapReachedException()).when(magicLinks).request(any(), any(), any());

    mvc.perform(
            post("/auth/magic-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"lang\":\"vi\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:rate-limited"))
        .andExpect(jsonPath("$.reason").value("mail_daily_cap"))
        .andExpect(jsonPath("$.trace_id").exists())
        .andExpect(jsonPath("$.detail").value("Too many login mails today, try again later"));

    assertThat(capCounter()).isEqualTo(before + 1);
  }
}
