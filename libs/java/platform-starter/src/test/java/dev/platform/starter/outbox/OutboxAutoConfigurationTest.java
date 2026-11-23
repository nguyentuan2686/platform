package dev.platform.starter.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/** Wiring only: nothing here connects to a database or broker. */
class OutboxAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
          .withUserConfiguration(Infrastructure.class)
          // Keep the relay thread from touching the (unreachable) database during the test.
          .withPropertyValues("platform.outbox.poll-interval=1h");

  @Test
  void registers_writer_relay_and_own_scheduler_without_enabling_scheduled_annotations() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(OutboxWriter.class);
          assertThat(context).hasSingleBean(OutboxRelay.class);
          assertThat(context).hasSingleBean(OutboxRelayScheduler.class);
          assertThat(context.getBean(OutboxRelayScheduler.class).isRunning()).isTrue();
          assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
          assertThat(context)
              .doesNotHaveBean(
                  "org.springframework.context.annotation.internalScheduledAnnotationProcessor");
        });
  }

  @Test
  void scheduler_stops_when_context_closes() {
    OutboxRelayScheduler[] scheduler = new OutboxRelayScheduler[1];
    runner.run(context -> scheduler[0] = context.getBean(OutboxRelayScheduler.class));
    assertThat(scheduler[0].isRunning()).isFalse();
  }

  @Test
  void disabled_by_property() {
    runner
        .withPropertyValues("platform.outbox.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(OutboxWriter.class);
              assertThat(context).doesNotHaveBean(OutboxRelay.class);
              assertThat(context).doesNotHaveBean(OutboxRelayScheduler.class);
            });
  }

  @Test
  void backs_off_without_kafka_template() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
        .withUserConfiguration(DatabaseOnly.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(OutboxRelay.class);
              assertThat(context).doesNotHaveBean(OutboxRelayScheduler.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class DatabaseOnly {

    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none");
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Infrastructure extends DatabaseOnly {

    @Bean
    KafkaTemplate<String, String> kafkaTemplate() {
      return new KafkaTemplate<>(
          new DefaultKafkaProducerFactory<>(
              Map.of(
                  ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                  ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                  ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }
  }
}
