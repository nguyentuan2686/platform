package dev.platform.payment.adapter.out.paypal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.payment.application.PayPalCheckout;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.Provider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class FakePayPalAdapterTest {

  static final String SECRET = "local-test-paypal-webhook-secret";
  final FakePayPalAdapter adapter = new FakePayPalAdapter(SECRET);

  static String hmac(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void checkout_has_a_fake_id_and_an_invalid_host_approve_url() {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV234567"),
            UUID.randomUUID(),
            "tuvi",
            "CREDIT_1",
            null,
            Money.of("1.19", "USD"),
            Provider.PAYPAL,
            Instant.now(),
            Duration.ofMinutes(30));

    PayPalCheckout checkout = adapter.createCheckout(order);

    assertThat(checkout.paypalOrderId()).matches("FAKE-PP-[0-9a-f-]{36}");
    assertThat(checkout.approveUrl())
        .hasToString("https://paypal.fake.invalid/checkoutnow?token=" + checkout.paypalOrderId());
  }

  @Test
  void webhook_is_valid_only_with_the_hmac_of_the_exact_body() throws Exception {
    byte[] body = "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(adapter.verifyWebhook(Map.of("X-Fake-PayPal-Signature", hmac(SECRET, body)), body))
        .isTrue();
    assertThat(adapter.verifyWebhook(Map.of("x-fake-paypal-signature", hmac(SECRET, body)), body))
        .isTrue();
    assertThat(adapter.verifyWebhook(Map.of("X-Fake-PayPal-Signature", hmac("other", body)), body))
        .isFalse();
    assertThat(adapter.verifyWebhook(Map.of("X-Fake-PayPal-Signature", "zz"), body)).isFalse();
    assertThat(adapter.verifyWebhook(Map.of(), body)).isFalse();
    assertThat(
            adapter.verifyWebhook(
                Map.of("X-Fake-PayPal-Signature", hmac(SECRET, body)), "tampered".getBytes()))
        .isFalse();
  }

  @Test
  void blank_secret_never_verifies() throws Exception {
    byte[] body = "x".getBytes(StandardCharsets.UTF_8);
    assertThat(
            new FakePayPalAdapter("").verifyWebhook(Map.of("X-Fake-PayPal-Signature", "00"), body))
        .isFalse();
  }

  @Test
  void refund_returns_a_fake_refund_id() {
    assertThat(adapter.refundCapture("cap-1", Money.of("1.19", "USD")))
        .startsWith("FAKE-PP-REFUND-");
  }
}
