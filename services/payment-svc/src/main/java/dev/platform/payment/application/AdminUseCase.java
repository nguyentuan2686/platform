package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCodeMatcher;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.PaymentSucceeded;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import dev.platform.payment.domain.UnmatchedReason;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/** The operator's tools: the "chua khop" queue and manual refunds. Used by Tuan only. */
public class AdminUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AdminUseCase.class);
  private static final Set<String> RESOLUTIONS = Set.of("REFUNDED_MANUALLY", "IGNORED");

  /** A queue row. The content is shown here only, to the operator, and is never logged. */
  public record UnmatchedView(
      long id,
      Provider provider,
      Money amount,
      Instant occurredAt,
      UnmatchedReason reason,
      String content,
      List<String> candidateCodes) {

    @Override
    public String toString() {
      return "UnmatchedView[" + id + ", content=[redacted]]";
    }
  }

  private final OrderRepositoryPort orders;
  private final ProviderTransactionPort transactions;
  private final RefundRequestPort refunds;
  private final PaymentEventsPort events;
  private final Clock clock;
  private final TransactionOperations tx;

  public AdminUseCase(
      OrderRepositoryPort orders,
      ProviderTransactionPort transactions,
      RefundRequestPort refunds,
      PaymentEventsPort events,
      Clock clock,
      TransactionOperations tx) {
    this.orders = orders;
    this.transactions = transactions;
    this.refunds = refunds;
    this.events = events;
    this.clock = clock;
    this.tx = tx;
  }

  public List<UnmatchedView> unmatched() {
    return transactions.listUnmatched().stream()
        .map(
            s ->
                new UnmatchedView(
                    s.id(),
                    s.tx().provider(),
                    s.tx().amount(),
                    s.tx().occurredAt(),
                    s.reason(),
                    s.tx().content(),
                    List.copyOf(OrderCodeMatcher.candidates(s.tx().content()))))
        .toList();
  }

  /** Pays a PENDING order with an unmatched transfer; 409 {@code order_not_pending|amount_low}. */
  public void assign(long transactionId, String orderCode) {
    tx.executeWithoutResult(status -> doAssign(transactionId, orderCode));
  }

  private void doAssign(long transactionId, String orderCode) {
    StoredTransaction stored =
        transactions
            .findUnmatchedForUpdate(transactionId)
            .orElseThrow(() -> new NotFoundException("transaction", transactionId));
    String code = orderCode == null ? "" : orderCode.trim().toUpperCase(Locale.ROOT);
    Order known =
        orders.findByCodes(Set.of(code)).stream()
            .findFirst()
            .orElseThrow(() -> new NotFoundException("order", code));
    Order order = orders.findForUpdate(known.id()).orElse(known);
    if (order.status() != OrderStatus.PENDING) {
      throw new ConflictException("order_not_pending");
    }
    BankTransaction bank = stored.tx();
    if (!order.amount().currency().equals(bank.amount().currency())
        || bank.amount().isLessThan(order.amount())) {
      throw new ConflictException("amount_low");
    }
    PaymentSucceeded event = order.markPaid(bank.amount(), bank.providerTxId(), clock.instant());
    orders.save(order);
    events.publish(event);
    transactions.assign(transactionId, order.id());
    LOG.info("transaction {} assigned to order_id={}", transactionId, order.id());
  }

  /** {@code resolution} is REFUNDED_MANUALLY or IGNORED. */
  public void resolve(long transactionId, String resolution, String note) {
    if (resolution == null || !RESOLUTIONS.contains(resolution)) {
      throw new OrderValidationException("resolution", "must be REFUNDED_MANUALLY or IGNORED");
    }
    tx.executeWithoutResult(
        status -> {
          transactions
              .findUnmatchedForUpdate(transactionId)
              .orElseThrow(() -> new NotFoundException("transaction", transactionId));
          transactions.resolve(transactionId, resolution, note);
        });
    LOG.info("transaction {} resolved as {}", transactionId, resolution);
  }

  public List<RefundRequest> refundRequests(RefundStatus status) {
    return refunds.list(status);
  }

  /** Tuan sent the money by hand: request DONE, order REFUNDED. Repeating is a no-op. */
  public RefundRequest completeRefund(UUID requestId, String note) {
    return tx.execute(
        status -> {
          RefundRequest request =
              refunds
                  .findForUpdate(requestId)
                  .orElseThrow(() -> new NotFoundException("refund request", requestId));
          if (request.status() == RefundStatus.DONE) {
            return request;
          }
          orders
              .findForUpdate(request.orderId())
              .ifPresent(
                  order -> {
                    if (order.status() == OrderStatus.REFUND_REQUESTED) {
                      order.completeRefund();
                      orders.save(order);
                    }
                  });
          RefundRequest done = request.complete(null, note, clock.instant());
          refunds.save(done);
          LOG.info("refund request {} completed", requestId);
          return done;
        });
  }
}
