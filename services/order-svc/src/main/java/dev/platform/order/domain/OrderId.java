package dev.platform.order.domain;

import java.util.UUID;

public record OrderId(UUID value) {

  public OrderId {
    if (value == null) {
      throw new IllegalArgumentException("order id must not be null");
    }
  }

  public static OrderId newId() {
    return new OrderId(UUID.randomUUID());
  }

  public static OrderId of(String raw) {
    return new OrderId(UUID.fromString(raw));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
