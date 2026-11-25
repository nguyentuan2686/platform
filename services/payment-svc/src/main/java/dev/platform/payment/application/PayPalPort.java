package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import java.util.Map;

public interface PayPalPort {

  PayPalCheckout createCheckout(Order order);

  /** True when the webhook signature over the exact body is valid. */
  boolean verifyWebhook(Map<String, String> headers, byte[] body);

  /** Refunds a captured payment and returns the provider's refund id. */
  String refundCapture(String captureId, Money amount);
}
