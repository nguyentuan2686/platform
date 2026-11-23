package dev.platform.starter.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: an {@link OutboxWriter} that joins the caller's transaction and an {@link
 * OutboxRelay} that publishes pending rows to Kafka every {@code platform.outbox.poll-interval}.
 *
 * <p>Active when the service has a {@link DataSource}, a transaction manager and a {@link
 * KafkaTemplate} (i.e. it added JDBC and Kafka starters); switch off with {@code
 * platform.outbox.enabled=false}. Services must add {@code classpath:db/platform} to {@code
 * spring.flyway.locations} so the {@code outbox_event} table exists.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
    })
@ConditionalOnClass({
  JdbcTemplate.class,
  KafkaTemplate.class,
  JsonMapper.class,
  GlobalOpenTelemetry.class
})
@ConditionalOnBooleanProperty(name = "platform.outbox.enabled", matchIfMissing = true)
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class, KafkaTemplate.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

  /** Upper bound for waiting on an in-flight relay cycle at shutdown. */
  static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

  @Bean
  @ConditionalOnMissingBean(OutboxWriter.class)
  JdbcOutboxWriter outboxWriter(DataSource dataSource, ObjectProvider<JsonMapper> jsonMapper) {
    return new JdbcOutboxWriter(dataSource, jsonMapper.getIfAvailable(JsonMapper::shared));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  OutboxRelay outboxRelay(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      KafkaTemplate<?, ?> kafkaTemplate,
      ObjectProvider<JsonMapper> jsonMapper,
      OutboxProperties properties) {
    return new OutboxRelay(
        dataSource,
        transactionManager,
        kafkaTemplate,
        jsonMapper.getIfAvailable(JsonMapper::shared),
        properties);
  }

  /**
   * Runs the relay on its own single thread (no application-wide {@code @EnableScheduling}). Stops
   * before the relay is closed: lifecycle beans stop before singletons are destroyed.
   */
  @Bean
  @ConditionalOnMissingBean
  OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, OutboxProperties properties) {
    return new OutboxRelayScheduler(relay::poll, properties.pollInterval(), STOP_TIMEOUT);
  }
}
