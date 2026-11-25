package dev.platform.payment.adapter.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic the outbox relay publishes to, so a broker with auto-create off does not stall
 * the relay. {@code KafkaAdmin} creates it at startup when missing.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfiguration {

  @Bean
  NewTopic paymentSucceededTopic() {
    return TopicBuilder.name("payments.payment-succeeded.v1").partitions(1).replicas(1).build();
  }
}
