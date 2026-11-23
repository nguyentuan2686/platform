package dev.platform.order.application;

import dev.platform.order.domain.OrderLine;
import java.util.List;

public record PlaceOrderCommand(String customerId, List<OrderLine> lines) {}
