package dev.platform.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Order {

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;
  private final OrderStatus status;
  private final Instant createdAt;
  private final List<OrderPlaced> events = new ArrayList<>();

  private Order(
      OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, Instant createdAt) {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (customerId == null || customerId.isBlank()) {
      throw new IllegalArgumentException("customerId must not be blank");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("order needs at least one line");
    }
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.status = status;
    this.createdAt = createdAt;
  }

  public static Order place(OrderId id, String customerId, List<OrderLine> lines, Instant now) {
    Order order = new Order(id, customerId, lines, OrderStatus.PLACED, now);
    order.events.add(new OrderPlaced(id, customerId, order.total(), order.itemCount(), now));
    return order;
  }

  /** Rebuilds an existing order (e.g. from persistence) without emitting events. */
  public static Order restore(
      OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, Instant createdAt) {
    return new Order(id, customerId, lines, status, createdAt);
  }

  public BigDecimal total() {
    return lines.stream()
        .map(OrderLine::subtotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private int itemCount() {
    return lines.stream().mapToInt(OrderLine::quantity).sum();
  }

  public List<OrderPlaced> pullEvents() {
    List<OrderPlaced> out = List.copyOf(events);
    events.clear();
    return out;
  }

  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<OrderLine> lines() {
    return lines;
  }

  public OrderStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
