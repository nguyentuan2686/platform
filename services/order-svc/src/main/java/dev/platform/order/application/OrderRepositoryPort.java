package dev.platform.order.application;

import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import java.util.Optional;

public interface OrderRepositoryPort {
  void save(Order order);

  Optional<Order> findById(OrderId id);
}
