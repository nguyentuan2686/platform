package dev.platform.order.adapter.out.messaging;

import dev.platform.order.application.OrderEventsPort;
import dev.platform.order.domain.OrderPlaced;
import dev.platform.starter.outbox.OutboxEvent;
import dev.platform.starter.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Appends OrderPlaced to the transactional outbox, inside the caller's transaction. */
@Component
public class OutboxOrderEventsAdapter implements OrderEventsPort {

  static final String TOPIC = "orders.order-placed.v1";
  static final String EVENT_TYPE = "OrderPlaced";

  private final OutboxWriter outbox;
  private final JsonMapper json;

  public OutboxOrderEventsAdapter(OutboxWriter outbox, JsonMapper json) {
    this.outbox = outbox;
    this.json = json;
  }

  @Override
  public void publish(OrderPlaced event) {
    ObjectNode data = json.createObjectNode();
    data.put("order_id", event.orderId().toString());
    data.put("customer_id", event.customerId());
    data.put("total", event.total().toPlainString());
    data.put("item_count", event.itemCount());
    outbox.append(
        new OutboxEvent(
            UUID.randomUUID(),
            EVENT_TYPE,
            TOPIC,
            event.orderId().toString(),
            event.occurredAt(),
            data));
  }
}
