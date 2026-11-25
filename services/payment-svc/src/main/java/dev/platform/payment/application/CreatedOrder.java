package dev.platform.payment.application;

import dev.platform.payment.domain.Order;

public record CreatedOrder(Order order, PaymentInstructions payment) {}
