package dev.platform.order.adapter.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic the outbox relay publishes to, so a broker with auto-create off (the k8s
 * Redpanda) does not stall the relay. {@code KafkaAdmin} creates it at startup when missing.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfiguration {

  @Bean
  NewTopic orderPlacedTopic() {
    return TopicBuilder.name("orders.order-placed.v1").partitions(1).replicas(1).build();
  }
}
