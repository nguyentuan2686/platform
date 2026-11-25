package dev.platform.payment.application;

import dev.platform.payment.domain.Order;

/** An order with the status to display (PENDING past its window reads EXPIRED). */
public record OrderView(Order order, String status) {}
