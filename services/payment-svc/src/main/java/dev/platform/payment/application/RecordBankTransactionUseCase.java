package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCodeMatcher;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.PaymentSucceeded;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.UnmatchedReason;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Applies one provider transaction: records it (unique per provider transaction, so retries,
 * webhook plus reconciliation, and two replicas yield one row), finds the order and pays it. All in
 * one transaction with the outbox event. Logs ids and outcomes only, never the transfer content.
 */
public class RecordBankTransactionUseCase implements BankTransactionRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(RecordBankTransactionUseCase.class);

  /** Finds the order a transaction is meant for. */
  interface OrderLookup {
    Resolution find(BankTransaction tx);
  }

  sealed interface Resolution {}

  record Found(Order order) implements Resolution {}

  record Missing(UnmatchedReason reason) implements Resolution {}

  private final OrderRepositoryPort orders;
  private final ProviderTransactionPort transactions;
  private final PaymentEventsPort events;
  private final TransactionOperations tx;

  public RecordBankTransactionUseCase(
      OrderRepositoryPort orders,
      ProviderTransactionPort transactions,
      PaymentEventsPort events,
      TransactionOperations tx) {
    this.orders = orders;
    this.transactions = transactions;
    this.events = events;
    this.tx = tx;
  }

  /** A bank transfer: the order is found by the code in the transfer content. */
  @Override
  public MatchOutcome record(BankTransaction transaction, ReceivedVia via) {
    return settle(transaction, via, this::byCode, false);
  }

  MatchOutcome settle(
      BankTransaction transaction, ReceivedVia via, OrderLookup lookup, boolean exactAmount) {
    MatchOutcome outcome = tx.execute(status -> apply(transaction, via, lookup, exactAmount));
    LOG.info(
        "provider transaction {} {} via {}: {}",
        transaction.provider(),
        transaction.providerTxId(),
        via,
        outcome);
    return outcome;
  }

  Resolution byProviderRef(String providerRef) {
    return orders
        .findByProviderRef(providerRef)
        .<Resolution>map(Found::new)
        .orElseGet(() -> new Missing(UnmatchedReason.UNKNOWN_CODE));
  }

  private MatchOutcome apply(
      BankTransaction transaction, ReceivedVia via, OrderLookup lookup, boolean exactAmount) {
    OptionalLong inserted = transactions.insertIfAbsent(transaction, via);
    if (inserted.isEmpty()) {
      return new MatchOutcome.Duplicate();
    }
    long id = inserted.getAsLong();
    Resolution resolution = lookup.find(transaction);
    if (resolution instanceof Missing missing) {
      return unmatched(id, missing.reason(), null);
    }
    Order found = ((Found) resolution).order();
    Order order = orders.findForUpdate(found.id()).orElse(found);
    if (order.status() != OrderStatus.PENDING) {
      return unmatched(id, UnmatchedReason.ALREADY_PAID, order);
    }
    boolean wrongAmount =
        !order.amount().currency().equals(transaction.amount().currency())
            || transaction.amount().isLessThan(order.amount())
            || (exactAmount && transaction.amount().isGreaterThan(order.amount()));
    if (wrongAmount) {
      return unmatched(id, UnmatchedReason.AMOUNT_LOW, order);
    }
    PaymentSucceeded event =
        order.markPaid(transaction.amount(), transaction.providerTxId(), transaction.occurredAt());
    orders.save(order);
    events.publish(event);
    transactions.markMatched(id, order.id());
    return new MatchOutcome.Matched(order.id());
  }

  private MatchOutcome unmatched(long id, UnmatchedReason reason, Order order) {
    transactions.markUnmatched(id, reason, order == null ? null : order.id());
    return new MatchOutcome.Unmatched(reason);
  }

  private Resolution byCode(BankTransaction transaction) {
    Set<String> candidates = OrderCodeMatcher.candidates(transaction.content());
    if (candidates.isEmpty()) {
      return new Missing(UnmatchedReason.NO_CODE);
    }
    List<Order> known = orders.findByCodes(candidates);
    if (known.isEmpty()) {
      return new Missing(UnmatchedReason.UNKNOWN_CODE);
    }
    if (known.size() > 1) {
      return new Missing(UnmatchedReason.AMBIGUOUS);
    }
    return new Found(known.get(0));
  }
}
