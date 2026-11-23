package dev.platform.order.application;

import dev.platform.order.domain.Order;

public interface PlaceOrderUseCase {
  Order place(PlaceOrderCommand command);
}
