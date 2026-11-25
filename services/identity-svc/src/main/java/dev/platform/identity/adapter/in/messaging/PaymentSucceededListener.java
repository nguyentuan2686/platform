package dev.platform.identity.adapter.in.messaging;

import dev.platform.identity.application.PaymentGrantUseCase;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies {@code PaymentSucceeded} events. Anything it cannot apply (not JSON, wrong envelope,
 * unknown product or account) throws, so the error handler retries and then dead-letters it. Only
 * ids are logged.
 */
@Component
public class PaymentSucceededListener {

  public static final String TOPIC = "payments.payment-succeeded.v1";
  public static final String GROUP = "identity-svc";

  private static final Logger log = LoggerFactory.getLogger(PaymentSucceededListener.class);
  private static final JsonMapper JSON = JsonMapper.shared();

  private final PaymentGrantUseCase grants;

  public PaymentSucceededListener(PaymentGrantUseCase grants) {
    this.grants = grants;
  }

  @KafkaListener(topics = TOPIC, groupId = GROUP)
  public void onMessage(String payload) {
    JsonNode root;
    try {
      root = JSON.readTree(payload);
    } catch (RuntimeException e) {
      throw new InvalidEventException("Payload is not JSON");
    }
    if (root == null || !"PaymentSucceeded".equals(text(root, "event_type"))) {
      throw new InvalidEventException("Not a PaymentSucceeded envelope");
    }
    UUID eventId = uuid(root, "event_id");
    JsonNode data = root.get("data");
    if (data == null || !data.isObject()) {
      throw new InvalidEventException("Missing data");
    }
    UUID orderId = uuid(data, "order_id");
    UUID accountId = uuid(data, "account_id");
    String productCode = text(data, "product_code");
    if (productCode == null) {
      throw new InvalidEventException("Missing product_code");
    }
    try {
      grants.grant(eventId, orderId, accountId, productCode);
    } catch (RuntimeException e) {
      // Ids and the exception type only: never the payload.
      log.warn(
          "PaymentSucceeded not applied event_id={} order_id={} cause={}",
          eventId,
          orderId,
          e.getClass().getSimpleName());
      throw e;
    }
    log.info("PaymentSucceeded applied event_id={} order_id={}", eventId, orderId);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field);
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      throw new InvalidEventException("Missing or invalid " + field);
    }
  }

  /** The event does not satisfy the contract. */
  static final class InvalidEventException extends IllegalArgumentException {
    InvalidEventException(String message) {
      super(message);
    }
  }
}
