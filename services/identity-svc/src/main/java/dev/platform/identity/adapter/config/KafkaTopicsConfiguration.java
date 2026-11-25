package dev.platform.identity.adapter.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the consumed topic and its dead-letter topic. Spring's {@code KafkaAdmin} creates them
 * at startup when missing (no-op when they exist), so brokers with auto-create off still work; a
 * missing {@code .DLT} would otherwise make the dead-letter recoverer fail and block the partition.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfiguration {

  private static final String TOPIC = "payments.payment-succeeded.v1";

  @Bean
  NewTopic paymentSucceededTopic() {
    return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic paymentSucceededDeadLetterTopic() {
    return TopicBuilder.name(TOPIC + ".DLT").partitions(1).replicas(1).build();
  }
}
