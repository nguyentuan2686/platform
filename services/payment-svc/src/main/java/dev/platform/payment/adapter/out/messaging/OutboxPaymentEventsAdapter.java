package dev.platform.payment.adapter.out.messaging;

import dev.platform.payment.application.PaymentEventsPort;
import dev.platform.payment.domain.PaymentSucceeded;
import dev.platform.starter.outbox.OutboxEvent;
import dev.platform.starter.outbox.OutboxWriter;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends PaymentSucceeded to the transactional outbox, inside the caller's transaction. Topic
 * payments.payment-succeeded.v1, key account_id (per-account ordering), envelope per CLAUDE.md.
 */
public class OutboxPaymentEventsAdapter implements PaymentEventsPort {

  static final String TOPIC = "payments.payment-succeeded.v1";
  static final String EVENT_TYPE = "PaymentSucceeded";

  private final OutboxWriter outbox;
  private final JsonMapper json;

  public OutboxPaymentEventsAdapter(OutboxWriter outbox, JsonMapper json) {
    this.outbox = outbox;
    this.json = json;
  }

  @Override
  public void publish(PaymentSucceeded event) {
    ObjectNode data = json.createObjectNode();
    data.put("order_id", event.orderId().toString());
    data.put("order_code", event.orderCode());
    data.put("account_id", event.accountId().toString());
    data.put("product_code", event.productCode());
    data.put("site", event.site());
    data.put("amount", event.amount().toPlainString());
    data.put("currency", event.amount().currency());
    data.put("provider", event.provider().name());
    data.put("paid_at", event.paidAt().toString());
    if (event.chartProfileId() != null) {
      data.put("chart_profile_id", event.chartProfileId().toString());
    }
    outbox.append(
        new OutboxEvent(
            UUID.randomUUID(),
            EVENT_TYPE,
            TOPIC,
            event.accountId().toString(),
            event.paidAt(),
            data));
  }
}
