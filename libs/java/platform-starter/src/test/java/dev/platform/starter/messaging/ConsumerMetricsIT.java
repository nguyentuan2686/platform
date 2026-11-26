package dev.platform.starter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.redpanda.RedpandaContainer;

/** No collector anywhere: the SDK reader is in memory, startup needs no exporter. */
@SpringBootTest(
    classes = ConsumerMetricsIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.kafka.consumer.auto-offset-reset=earliest"})
class ConsumerMetricsIT {

  static final Duration DEADLINE = Duration.ofSeconds(30);
  static final String TOPIC = "metrics-it-" + UUID.randomUUID();
  static final String TOPIC_PROPERTY = "metrics.it.topic";
  static final String GROUP = "metrics-it-group";

  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.1")
          .withCommand("redpanda", "start", "--mode=dev-container", "--smp=1", "--memory=512M");

  static {
    REDPANDA.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    registry.add(TOPIC_PROPERTY, () -> TOPIC);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
  static class App {

    static final InMemoryMetricReader READER = InMemoryMetricReader.create();

    @Bean
    OpenTelemetry openTelemetry() {
      return OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(READER).build())
          .build();
    }

    @Bean
    DefaultErrorHandler errorHandler() {
      return new DefaultErrorHandler(new FixedBackOff(0, 1));
    }

    @Bean
    Listener listener() {
      return new Listener();
    }
  }

  static class Listener {

    @KafkaListener(topics = "${metrics.it.topic}", groupId = GROUP)
    void onMessage(String payload) {
      if ("boom".equals(payload)) {
        throw new IllegalStateException("boom");
      }
    }
  }

  @Autowired KafkaTemplate<String, String> kafka;

  long count(String outcome) {
    return App.READER.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("messaging.process.duration"))
        .flatMap(m -> m.getHistogramData().getPoints().stream())
        .filter(
            p ->
                outcome.equals(p.getAttributes().get(ConsumerMetricsInterceptor.OUTCOME))
                    && TOPIC.equals(p.getAttributes().get(ConsumerMetricsInterceptor.TOPIC))
                    && GROUP.equals(p.getAttributes().get(ConsumerMetricsInterceptor.GROUP)))
        .mapToLong(HistogramPointData::getCount)
        .sum();
  }

  @Test
  void listener_success_counts_processed() {
    kafka.send(TOPIC, "ok");
    Awaitility.await().atMost(DEADLINE).until(() -> count("processed") >= 1);
    assertThat(count("processed")).isEqualTo(1);
    assertThat(count("failed")).isZero();
  }

  @Test
  void failing_listener_counts_failed_per_attempt() {
    kafka.send(TOPIC, "boom");
    // One failure per attempt: the first delivery and its single retry.
    Awaitility.await().atMost(DEADLINE).until(() -> count("failed") >= 2);
    assertThat(count("failed")).isEqualTo(2);
  }
}
