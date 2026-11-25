package dev.platform.identity.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaTopicsConfigurationTest {

  @Test
  void declares_payment_succeeded_topic_and_its_dead_letter_topic() {
    KafkaTopicsConfiguration config = new KafkaTopicsConfiguration();

    assertThat(List.of(config.paymentSucceededTopic(), config.paymentSucceededDeadLetterTopic()))
        .extracting(t -> t.name())
        .containsExactly("payments.payment-succeeded.v1", "payments.payment-succeeded.v1.DLT");
  }
}
