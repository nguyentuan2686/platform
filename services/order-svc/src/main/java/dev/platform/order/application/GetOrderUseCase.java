package dev.platform.order.application;

import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;

public interface GetOrderUseCase {
  Order get(OrderId id);
}
