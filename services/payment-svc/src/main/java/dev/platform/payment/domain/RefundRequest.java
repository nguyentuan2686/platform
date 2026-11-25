package dev.platform.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A customer's money refund for one order; one per order. */
public record RefundRequest(
    UUID id,
    UUID orderId,
    Provider provider,
    Money amount,
    RefundStatus status,
    String providerRefundId,
    String note,
    Instant createdAt,
    Instant completedAt) {

  public static RefundRequest open(UUID id, Order order, Instant now) {
    return new RefundRequest(
        id, order.id(), order.provider(), order.amount(), RefundStatus.OPEN, null, null, now, null);
  }

  public RefundRequest complete(String providerRefundId, String note, Instant at) {
    return new RefundRequest(
        id, orderId, provider, amount, RefundStatus.DONE, providerRefundId, note, createdAt, at);
  }
}
