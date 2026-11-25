package dev.platform.payment.application;

import dev.platform.payment.domain.Order;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class GetOrderUseCase {

  static final int HISTORY_LIMIT = 50;

  private final OrderRepositoryPort orders;
  private final Clock clock;

  public GetOrderUseCase(OrderRepositoryPort orders, Clock clock) {
    this.orders = orders;
    this.clock = clock;
  }

  /** Another account's order is indistinguishable from a missing one. */
  public OrderView get(UUID orderId, UUID accountId) {
    Order order =
        orders
            .find(orderId)
            .filter(o -> o.accountId().equals(accountId))
            .orElseThrow(() -> new NotFoundException("order", orderId));
    return view(order, clock.instant());
  }

  /** The account's newest orders (transaction history). */
  public List<OrderView> list(UUID accountId) {
    Instant now = clock.instant();
    return orders.findByAccount(accountId, HISTORY_LIMIT).stream().map(o -> view(o, now)).toList();
  }

  private static OrderView view(Order order, Instant now) {
    return new OrderView(order, order.viewStatus(now));
  }
}
