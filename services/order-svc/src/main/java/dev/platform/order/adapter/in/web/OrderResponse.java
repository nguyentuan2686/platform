package dev.platform.order.adapter.in.web;

import dev.platform.order.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    String id,
    String customerId,
    List<Item> items,
    BigDecimal total,
    String status,
    Instant createdAt) {

  public record Item(String sku, int quantity, BigDecimal unitPrice) {}

  static OrderResponse from(Order order) {
    return new OrderResponse(
        order.id().toString(),
        order.customerId(),
        order.lines().stream().map(l -> new Item(l.sku(), l.quantity(), l.unitPrice())).toList(),
        order.total(),
        order.status().name(),
        order.createdAt());
  }
}
