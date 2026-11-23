package dev.platform.order.domain;

import java.math.BigDecimal;

public record OrderLine(String sku, int quantity, BigDecimal unitPrice) {

  public OrderLine {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
    if (quantity < 1) {
      throw new IllegalArgumentException("quantity must be >= 1");
    }
    if (unitPrice == null || unitPrice.signum() < 0) {
      throw new IllegalArgumentException("unitPrice must be >= 0");
    }
  }

  BigDecimal subtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }
}
