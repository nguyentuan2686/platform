package dev.platform.payment.adapter.in.web;

import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.PayPalCapture;
import dev.platform.payment.application.PayPalWebhookUseCase;
import dev.platform.payment.domain.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PayPal webhook (open of the internal token, authenticated by the PayPal port's signature check).
 * Only PAYMENT.CAPTURE.COMPLETED does anything; other event types are acknowledged and ignored.
 */
@RestController
public class PayPalWebhookController {

  static final String CAPTURE_COMPLETED = "PAYMENT.CAPTURE.COMPLETED";

  private final PayPalWebhookUseCase useCase;
  private final JsonMapper json;
  private final Clock clock;

  public PayPalWebhookController(PayPalWebhookUseCase useCase, JsonMapper json, Clock clock) {
    this.useCase = useCase;
    this.json = json;
    this.clock = clock;
  }

  @PostMapping("/webhooks/paypal")
  Map<String, Boolean> receive(
      @RequestBody(required = false) byte[] raw, @RequestHeader Map<String, String> headers) {
    byte[] body = raw == null ? new byte[0] : raw;
    if (!useCase.verify(headers, body)) {
      throw new UnauthenticatedException();
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      throw malformed("body");
    }
    if (root == null || !root.isObject()) {
      throw malformed("body");
    }
    if (CAPTURE_COMPLETED.equals(root.path("event_type").asString(""))) {
      useCase.captureCompleted(capture(root));
    }
    return Map.of("success", true);
  }

  private PayPalCapture capture(JsonNode root) {
    JsonNode resource = root.path("resource");
    String captureId = resource.path("id").asString("");
    String paypalOrderId =
        resource.path("supplementary_data").path("related_ids").path("order_id").asString("");
    if (captureId.isBlank()) {
      throw malformed("resource.id");
    }
    if (paypalOrderId.isBlank()) {
      throw malformed("resource.supplementary_data.related_ids.order_id");
    }
    Money amount;
    try {
      amount =
          new Money(
              new BigDecimal(resource.path("amount").path("value").asString("")),
              resource.path("amount").path("currency_code").asString(""));
    } catch (NumberFormatException | ArithmeticException e) {
      throw malformed("resource.amount");
    }
    return new PayPalCapture(captureId, paypalOrderId, amount, time(resource, root));
  }

  private Instant time(JsonNode resource, JsonNode root) {
    for (JsonNode candidate :
        new JsonNode[] {resource.path("create_time"), root.path("create_time")}) {
      try {
        return Instant.parse(candidate.asString(""));
      } catch (DateTimeParseException e) {
        // try the next one
      }
    }
    return clock.instant();
  }

  private static OrderValidationException malformed(String field) {
    return new OrderValidationException(field, "Malformed webhook body");
  }
}
