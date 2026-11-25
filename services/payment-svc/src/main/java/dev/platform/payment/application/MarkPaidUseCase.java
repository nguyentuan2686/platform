package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.PaymentSucceeded;
import dev.platform.starter.error.NotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/** Marks an order paid and appends PaymentSucceeded to the outbox in the same transaction. */
public class MarkPaidUseCase {

  private final OrderRepositoryPort orders;
  private final PaymentEventsPort events;
  private final TransactionOperations tx;

  public MarkPaidUseCase(
      OrderRepositoryPort orders, PaymentEventsPort events, TransactionOperations tx) {
    this.orders = orders;
    this.events = events;
    this.tx = tx;
  }

  /**
   * Throws {@link NotFoundException} for an unknown order and {@link IllegalStateException} when
   * the order is not pending (a second payment never publishes a second event).
   */
  public void markPaid(UUID orderId, Money received, String providerRef, Instant at) {
    tx.executeWithoutResult(
        status -> {
          Order order =
              orders
                  .findForUpdate(orderId)
                  .orElseThrow(() -> new NotFoundException("order", orderId));
          PaymentSucceeded event = order.markPaid(received, providerRef, at);
          orders.save(order);
          events.publish(event);
        });
  }
}
