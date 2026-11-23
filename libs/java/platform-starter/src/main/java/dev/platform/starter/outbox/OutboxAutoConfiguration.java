package dev.platform.starter.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * platform.outbox.enabled=false}. When Flyway is present the {@code outbox_event} table is created
 * automatically from {@code classpath:db/platform} with its own history table {@code
 * platform_schema_history}, before the service's migrations.
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

  /**
   * Creates the {@code outbox_event} table through the starter's own Flyway history (see {@link
   * PlatformFlywayMigrator}) when the service uses Flyway; services do not list {@code
   * classpath:db/platform} themselves.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.flywaydb.core.Flyway",
        "org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer"
      })
  @ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
  static class PlatformMigrationsConfiguration {

    @Bean(PlatformFlywayMigrator.BEAN_NAME)
    PlatformFlywayMigrator platformFlywayMigrator(DataSource dataSource) {
      return new PlatformFlywayMigrator(dataSource);
    }

    @Bean
    static BeanFactoryPostProcessor platformMigrationsBeforeServiceMigrations() {
      return PlatformFlywayMigrator.runBeforeServiceMigrations();
    }

    /**
     * Platform tables and {@code platform_schema_history} exist before the service's first
     * migration, so its schema is no longer empty: baseline at version 0 so that every service
     * migration (V1 onwards) still applies. Left alone if the service configured baselining itself.
     */
    @Bean
    FlywayConfigurationCustomizer platformServiceFlywayBaseline() {
      return configuration -> {
        if (!configuration.isBaselineOnMigrate()) {
          configuration.baselineOnMigrate(true).baselineVersion("0");
        }
      };
    }
  }
}
