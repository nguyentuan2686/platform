package dev.platform.order.application;

import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

public class OrderService implements PlaceOrderUseCase, GetOrderUseCase {

  private final OrderRepositoryPort repo;
  private final OrderEventsPort events;
  private final Clock clock;

  public OrderService(OrderRepositoryPort repo, OrderEventsPort events, Clock clock) {
    this.repo = repo;
    this.events = events;
    this.clock = clock;
  }

  @Override
  @Transactional
  public Order place(PlaceOrderCommand command) {
    Order order =
        Order.place(OrderId.newId(), command.customerId(), command.lines(), clock.instant());
    repo.save(order);
    order.pullEvents().forEach(events::publish);
    return order;
  }

  @Override
  public Order get(OrderId id) {
    return repo.findById(id).orElseThrow(() -> new NotFoundException("order", id));
  }
}
