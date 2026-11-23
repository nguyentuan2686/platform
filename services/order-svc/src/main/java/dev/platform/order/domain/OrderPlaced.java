package dev.platform.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderPlaced(
    OrderId orderId, String customerId, BigDecimal total, int itemCount, Instant occurredAt) {}
