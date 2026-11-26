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
  // Each test owns a topic and a consumer group, so the cumulative reader never mixes them and
  // the tests pass in any order.
  static final String RUN = UUID.randomUUID().toString();
  static final String OK_TOPIC = "metrics-it-ok-" + RUN;
  static final String OK_GROUP = "metrics-it-ok-group";
  static final String BOOM_TOPIC = "metrics-it-boom-" + RUN;
  static final String BOOM_GROUP = "metrics-it-boom-group";

  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.1")
          .withCommand("redpanda", "start", "--mode=dev-container", "--smp=1", "--memory=512M");

  static {
    REDPANDA.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    registry.add("metrics.it.ok-topic", () -> OK_TOPIC);
    registry.add("metrics.it.boom-topic", () -> BOOM_TOPIC);
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

    @KafkaListener(topics = "${metrics.it.ok-topic}", groupId = OK_GROUP)
    void onOk(String payload) {}

    @KafkaListener(topics = "${metrics.it.boom-topic}", groupId = BOOM_GROUP)
    void onBoom(String payload) {
      throw new IllegalStateException("boom");
    }
  }

  @Autowired KafkaTemplate<String, String> kafka;

  long count(String topic, String group, String outcome) {
    return App.READER.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("messaging.process.duration"))
        .flatMap(m -> m.getHistogramData().getPoints().stream())
        .filter(
            p ->
                outcome.equals(p.getAttributes().get(ConsumerMetricsInterceptor.OUTCOME))
                    && topic.equals(p.getAttributes().get(ConsumerMetricsInterceptor.TOPIC))
                    && group.equals(p.getAttributes().get(ConsumerMetricsInterceptor.GROUP)))
        .mapToLong(HistogramPointData::getCount)
        .sum();
  }

  @Test
  void listener_success_counts_processed() {
    kafka.send(OK_TOPIC, "ok");
    Awaitility.await().atMost(DEADLINE).until(() -> count(OK_TOPIC, OK_GROUP, "processed") >= 1);
    assertThat(count(OK_TOPIC, OK_GROUP, "processed")).isEqualTo(1);
    assertThat(count(OK_TOPIC, OK_GROUP, "failed")).isZero();
  }

  @Test
  void failing_listener_counts_failed_per_attempt() {
    kafka.send(BOOM_TOPIC, "boom");
    // One failure per attempt: the first delivery and its single retry.
    Awaitility.await().atMost(DEADLINE).until(() -> count(BOOM_TOPIC, BOOM_GROUP, "failed") >= 2);
    assertThat(count(BOOM_TOPIC, BOOM_GROUP, "failed")).isEqualTo(2);
    assertThat(count(BOOM_TOPIC, BOOM_GROUP, "processed")).isZero();
  }
}
