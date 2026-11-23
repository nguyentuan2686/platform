package dev.platform.order.application;

import dev.platform.order.domain.OrderPlaced;

public interface OrderEventsPort {
  void publish(OrderPlaced event);
}
