package dev.platform.starter.messaging;

import dev.platform.starter.telemetry.PlatformMeter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Consumer RED metrics for every {@code @KafkaListener}: Spring Boot applies a single {@link
 * RecordInterceptor} bean to its listener container factory. Inactive without spring-kafka or the
 * OpenTelemetry API; a service that defines its own interceptor keeps it.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({RecordInterceptor.class, GlobalOpenTelemetry.class})
public class MessagingMetricsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(RecordInterceptor.class)
  ConsumerMetricsInterceptor consumerMetricsInterceptor(ObjectProvider<OpenTelemetry> otel) {
    return new ConsumerMetricsInterceptor(PlatformMeter.of(otel));
  }
}
