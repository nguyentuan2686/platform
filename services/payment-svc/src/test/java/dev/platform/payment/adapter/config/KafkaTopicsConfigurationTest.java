package dev.platform.payment.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaTopicsConfigurationTest {

  @Test
  void declares_payment_succeeded_topic() {
    assertThat(new KafkaTopicsConfiguration().paymentSucceededTopic().name())
        .isEqualTo("payments.payment-succeeded.v1");
  }
}
