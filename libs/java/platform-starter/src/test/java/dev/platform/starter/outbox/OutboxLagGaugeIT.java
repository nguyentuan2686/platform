package dev.platform.starter.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    classes = OutboxLagGaugeIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "platform.outbox.poll-interval=1h",
      // Never contacted: the relay has nothing to send in these tests.
      "spring.kafka.bootstrap-servers=localhost:1"
    })
class OutboxLagGaugeIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    static final InMemoryMetricReader READER = InMemoryMetricReader.create();

    @Bean
    OpenTelemetry openTelemetry() {
      return OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(READER).build())
          .build();
    }
  }

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from outbox_event");
  }

  void insertThirtySecondsOld(boolean published) {
    jdbc.update(
        "insert into outbox_event(event_id, event_type, topic, msg_key, payload, occurred_at,"
            + " published_at) values (?, 'T', 't', 'k', '{}'::jsonb,"
            + " now() - interval '30 seconds', "
            + (published ? "now()" : "null")
            + ")",
        UUID.randomUUID());
  }

  double lag() {
    var values =
        App.READER.collectAllMetrics().stream()
            .filter(m -> m.getName().equals("platform.outbox.lag"))
            .flatMap(m -> m.getDoubleGaugeData().getPoints().stream())
            .map(p -> p.getValue())
            .toList();
    assertThat(values).as("platform.outbox.lag observed").hasSize(1);
    return values.get(0);
  }

  @Test
  void lag_is_zero_without_pending_rows() {
    assertThat(lag()).isZero();
  }

  @Test
  void lag_reflects_oldest_unpublished_row() {
    insertThirtySecondsOld(false);
    assertThat(lag()).isGreaterThanOrEqualTo(30).isLessThan(60);
  }

  @Test
  void published_rows_do_not_count() {
    insertThirtySecondsOld(true);
    assertThat(lag()).isZero();
  }
}
