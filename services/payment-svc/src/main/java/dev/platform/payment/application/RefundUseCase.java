package dev.platform.payment.application;

import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Money refund of an unused credit pack (spec 13.2). The identity-svc call and the PayPal refund
 * run outside database transactions; identity's reversal is idempotent on the order id, so a crash
 * between the two steps is safe to retry.
 */
public class RefundUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RefundUseCase.class);

  public record Result(RefundRequest request, boolean created) {}

  private final OrderRepositoryPort orders;
  private final PriceCatalogPort catalog;
  private final IdentityCreditsPort identity;
  private final RefundRequestPort refunds;
  private final PayPalPort paypal;
  private final ProviderTransactionPort transactions;
  private final Clock clock;
  private final TransactionOperations tx;

  public RefundUseCase(
      OrderRepositoryPort orders,
      PriceCatalogPort catalog,
      IdentityCreditsPort identity,
      RefundRequestPort refunds,
      PayPalPort paypal,
      ProviderTransactionPort transactions,
      Clock clock,
      TransactionOperations tx) {
    this.orders = orders;
    this.catalog = catalog;
    this.identity = identity;
    this.refunds = refunds;
    this.paypal = paypal;
    this.transactions = transactions;
    this.clock = clock;
    this.tx = tx;
  }

  /**
   * Throws {@link NotFoundException} (no such order of this account), {@link ConflictException}
   * ({@code not_refundable}, {@code credits_used}) or {@link UpstreamUnavailableException}.
   */
  public Result request(UUID orderId, UUID accountId) {
    Order order =
        orders
            .find(orderId)
            .filter(o -> o.accountId().equals(accountId))
            .orElseThrow(() -> new NotFoundException("order", orderId));

    Optional<RefundRequest> existing = refunds.findByOrder(orderId);
    if (existing.isPresent()) {
      return new Result(settle(existing.get()), false);
    }
    if (order.status() != OrderStatus.PAID) {
      throw new ConflictException("not_refundable");
    }
    int credits =
        catalog
            .refundableCredits(order.productCode())
            .orElseThrow(() -> new ConflictException("not_refundable"));
    try {
      identity.reverse(accountId, orderId, credits);
    } catch (InsufficientCreditsException e) {
      throw new ConflictException("credits_used");
    }

    Result opened = tx.execute(status -> open(orderId));
    if (!opened.created()) {
      return new Result(settle(opened.request()), false);
    }
    LOG.info("refund requested order_id={} provider={}", orderId, order.provider());
    return new Result(settle(opened.request()), true);
  }

  private Result open(UUID orderId) {
    Order order =
        orders.findForUpdate(orderId).orElseThrow(() -> new NotFoundException("order", orderId));
    Optional<RefundRequest> concurrent = refunds.findByOrder(orderId);
    if (concurrent.isPresent()) {
      return new Result(concurrent.get(), false);
    }
    if (order.status() != OrderStatus.PAID) {
      throw new ConflictException("not_refundable");
    }
    order.requestRefund();
    orders.save(order);
    RefundRequest request = RefundRequest.open(UUID.randomUUID(), order, clock.instant());
    refunds.insert(request);
    return new Result(request, true);
  }

  /** PayPal refunds go out at once (and are retried on a repeat call); SePay ones wait for Tuan. */
  private RefundRequest settle(RefundRequest request) {
    if (request.status() != RefundStatus.OPEN || request.provider() != Provider.PAYPAL) {
      return request;
    }
    Optional<String> capture =
        transactions.matchedTransactionId(request.orderId(), Provider.PAYPAL);
    if (capture.isEmpty()) {
      return request; // no capture on record: a human refunds it from the PayPal dashboard
    }
    String providerRefundId;
    try {
      providerRefundId = paypal.refundCapture(capture.get(), request.amount());
    } catch (UpstreamUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      LOG.warn("paypal refund failed: {}", e.getClass().getSimpleName());
      throw new UpstreamUnavailableException("paypal refund failed");
    }
    return tx.execute(status -> complete(request.id(), providerRefundId));
  }

  private RefundRequest complete(UUID requestId, String providerRefundId) {
    RefundRequest current =
        refunds
            .findForUpdate(requestId)
            .orElseThrow(() -> new NotFoundException("refund request", requestId));
    if (current.status() == RefundStatus.DONE) {
      return current;
    }
    orders
        .findForUpdate(current.orderId())
        .ifPresent(
            order -> {
              if (order.status() == OrderStatus.REFUND_REQUESTED) {
                order.completeRefund();
                orders.save(order);
              }
            });
    RefundRequest done = current.complete(providerRefundId, null, clock.instant());
    refunds.save(done);
    return done;
  }
}
