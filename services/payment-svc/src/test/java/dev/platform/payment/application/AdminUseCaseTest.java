package dev.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import dev.platform.payment.domain.UnmatchedReason;
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.InMemoryRefundRequests;
import dev.platform.payment.support.RecordingEvents;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class AdminUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  final InMemoryOrders orders = new InMemoryOrders();
  final InMemoryProviderTransactions txs = new InMemoryProviderTransactions();
  final InMemoryRefundRequests refunds = new InMemoryRefundRequests();
  final RecordingEvents events = new RecordingEvents();
  final RecordBankTransactionUseCase recorder =
      new RecordBankTransactionUseCase(
          orders, txs, events, TransactionOperations.withoutTransaction());
  final AdminUseCase admin =
      new AdminUseCase(
          orders,
          txs,
          refunds,
          events,
          Clock.fixed(T0, ZoneOffset.UTC),
          TransactionOperations.withoutTransaction());

  Order order(String code) {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode(code),
            UUID.randomUUID(),
            "tuvi",
            "CREDIT_5",
            null,
            Money.of("119000", "VND"),
            Provider.SEPAY,
            T0,
            Duration.ofMinutes(30));
    orders.save(order);
    return order;
  }

  long unmatched(String id, String amount, String content) {
    recorder.record(
        new BankTransaction(Provider.SEPAY, id, Money.of(amount, "VND"), content, "FT", T0),
        ReceivedVia.WEBHOOK);
    return txs.rows.values().stream()
        .filter(r -> r.tx().providerTxId().equals(id))
        .findFirst()
        .orElseThrow()
        .id();
  }

  @Test
  void the_queue_lists_reason_amount_and_candidate_codes() {
    order("TV8K2QAB");
    unmatched("1", "100000", "TV8K2QAB va TV9M3RCD");

    var list = admin.unmatched();

    assertThat(list).hasSize(1);
    assertThat(list.get(0).reason()).isEqualTo(UnmatchedReason.AMOUNT_LOW);
    assertThat(list.get(0).candidateCodes()).containsExactly("TV8K2QAB", "TV9M3RCD");
    assertThat(list.get(0).content()).contains("TV8K2QAB");
  }

  @Test
  void assigning_a_no_code_transfer_pays_the_pending_order_once() {
    Order order = order("TV8K2QAB");
    long id = unmatched("1", "119000", "chuyen tien");

    admin.assign(id, "tv8k2qab");

    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(events.events).hasSize(1);
    assertThat(txs.rows.get(id).status()).isEqualTo("MATCHED");
    assertThat(admin.unmatched()).isEmpty();
  }

  @Test
  void assigning_an_underpayment_is_amount_low() {
    Order order = order("TV8K2QAB");
    long id = unmatched("1", "100000", "TV8K2QAB");

    assertThatThrownBy(() -> admin.assign(id, "TV8K2QAB"))
        .isInstanceOfSatisfying(
            ConflictException.class, e -> assertThat(e.reason()).isEqualTo("amount_low"));
    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(events.events).isEmpty();
  }

  @Test
  void assigning_to_a_paid_order_is_order_not_pending() {
    order("TV8K2QAB");
    unmatched("1", "119000", "TV8K2QAB");
    long second = unmatched("2", "119000", "TV8K2QAB");

    assertThatThrownBy(() -> admin.assign(second, "TV8K2QAB"))
        .isInstanceOfSatisfying(
            ConflictException.class, e -> assertThat(e.reason()).isEqualTo("order_not_pending"));
  }

  @Test
  void assigning_to_an_unknown_code_or_a_missing_transaction_is_not_found() {
    long id = unmatched("1", "119000", "chuyen tien");

    assertThatThrownBy(() -> admin.assign(id, "TV9M3RCD")).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> admin.assign(999, "TV9M3RCD")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void resolving_marks_the_transaction_resolved_and_leaves_the_queue() {
    long id = unmatched("1", "119000", "chuyen tien");

    admin.resolve(id, "REFUNDED_MANUALLY", "returned by hand");

    assertThat(txs.rows.get(id).status()).isEqualTo("RESOLVED");
    assertThat(txs.rows.get(id).resolution()).isEqualTo("REFUNDED_MANUALLY");
    assertThat(admin.unmatched()).isEmpty();
  }

  @Test
  void resolving_with_an_unknown_resolution_is_a_validation_error() {
    long id = unmatched("1", "119000", "chuyen tien");

    assertThatThrownBy(() -> admin.resolve(id, "WHATEVER", null))
        .isInstanceOf(OrderValidationException.class);
  }

  @Test
  void completing_a_refund_request_marks_it_done_and_the_order_refunded() {
    Order order = order("TV8K2QAB");
    order.markPaid(Money.of("119000", "VND"), "x", T0);
    order.requestRefund();
    RefundRequest request = RefundRequest.open(UUID.randomUUID(), order, T0);
    refunds.insert(request);

    assertThat(admin.refundRequests(RefundStatus.OPEN)).hasSize(1);
    RefundRequest done = admin.completeRefund(request.id(), "transferred");

    assertThat(done.status()).isEqualTo(RefundStatus.DONE);
    assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(admin.refundRequests(RefundStatus.OPEN)).isEmpty();
    assertThat(admin.completeRefund(request.id(), "again").status()).isEqualTo(RefundStatus.DONE);
  }

  @Test
  void completing_an_unknown_refund_request_is_not_found() {
    assertThatThrownBy(() -> admin.completeRefund(UUID.randomUUID(), null))
        .isInstanceOf(NotFoundException.class);
  }
}
