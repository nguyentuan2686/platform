package dev.platform.payment.adapter.out.paypal;

import dev.platform.payment.application.PayPalCheckout;
import dev.platform.payment.application.PayPalPort;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stand-in for PayPal until F3: no network, ids are FAKE-PP-uuid, the approve URL points at a
 * reserved .invalid host, and a webhook is valid when X-Fake-PayPal-Signature is the hex
 * HMAC-SHA256 of the exact body under the configured secret.
 */
public class FakePayPalAdapter implements PayPalPort {

  static final String SIGNATURE_HEADER = "X-Fake-PayPal-Signature";

  private final byte[] secret;

  public FakePayPalAdapter(String webhookSecret) {
    this.secret = webhookSecret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public PayPalCheckout createCheckout(Order order) {
    String id = "FAKE-PP-" + UUID.randomUUID();
    return new PayPalCheckout(
        id, URI.create("https://paypal.fake.invalid/checkoutnow?token=" + id));
  }

  @Override
  public boolean verifyWebhook(Map<String, String> headers, byte[] body) {
    if (secret.length == 0) {
      return false; // not configured: fail closed
    }
    String presented = header(headers, SIGNATURE_HEADER);
    if (presented == null) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] expected =
          HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
      return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  @Override
  public String refundCapture(String captureId, Money amount) {
    return "FAKE-PP-REFUND-" + UUID.randomUUID();
  }

  private static String header(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue();
      }
    }
    return null;
  }
}
