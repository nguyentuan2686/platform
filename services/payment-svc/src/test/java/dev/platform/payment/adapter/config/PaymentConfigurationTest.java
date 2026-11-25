package dev.platform.payment.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.payment.adapter.out.paypal.FakePayPalAdapter;
import org.junit.jupiter.api.Test;

class PaymentConfigurationTest {

  @Test
  void only_fake_paypal_mode_is_accepted() {
    PaymentConfiguration config = new PaymentConfiguration();

    assertThat(config.payPalPort("fake", "local-test-secret"))
        .isInstanceOf(FakePayPalAdapter.class);
    for (String mode : new String[] {"live", "sandbox", ""}) {
      assertThatThrownBy(() -> config.payPalPort(mode, "local-test-secret"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("real PayPal adapter lands in F3");
    }
  }
}
