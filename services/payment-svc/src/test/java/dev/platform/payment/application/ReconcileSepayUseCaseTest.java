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
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.RecordingEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class ReconcileSepayUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  final InMemoryOrders orders = new InMemoryOrders();
  final InMemoryProviderTransactions txs = new InMemoryProviderTransactions();
  final RecordingEvents events = new RecordingEvents();
  final RecordBankTransactionUseCase recorder =
      new RecordBankTransactionUseCase(
          orders, txs, events, TransactionOperations.withoutTransaction());
  final List<BankTransaction> upstream = new ArrayList<>();
  Duration askedLookback;
  RuntimeException failure;

  final ReconcileSepayUseCase useCase =
      new ReconcileSepayUseCase(
          lookback -> {
            askedLookback = lookback;
            if (failure != null) {
              throw failure;
            }
            return upstream;
          },
          recorder,
          Duration.ofHours(24));

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

  static BankTransaction bank(String id, String content) {
    return new BankTransaction(Provider.SEPAY, id, Money.of("119000", "VND"), content, "FT1", T0);
  }

  @Test
  void a_transaction_the_webhook_already_recorded_is_a_duplicate_without_a_second_event() {
    order("TV8K2QAB");
    BankTransaction tx = bank("1", "TV8K2QAB");
    recorder.record(tx, ReceivedVia.WEBHOOK);
    upstream.add(tx);

    ReconcileSepayUseCase.Summary summary = useCase.run();

    assertThat(askedLookback).isEqualTo(Duration.ofHours(24));
    assertThat(summary.duplicates()).isEqualTo(1);
    assertThat(summary.matched()).isZero();
    assertThat(events.events).hasSize(1);
  }

  @Test
  void a_missed_webhook_is_recovered_through_reconciliation() {
    Order order = order("TV8K2QAB");
    upstream.add(bank("1", "TV8K2QAB"));
    upstream.add(bank("2", "no code here"));

    ReconcileSepayUseCase.Summary summary = useCase.run();

    assertThat(summary.matched()).isEqualTo(1);
    assertThat(summary.unmatched()).isEqualTo(1);
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(txs.rows.get(1L).via()).isEqualTo(ReceivedVia.RECONCILE);
  }

  @Test
  void one_failing_transaction_does_not_stop_the_others() {
    Order order = order("TV8K2QAB");
    upstream.add(bank("1", "TV9M3RCD"));
    upstream.add(bank("2", "TV8K2QAB"));
    ReconcileSepayUseCase flaky =
        new ReconcileSepayUseCase(
            lookback -> upstream,
            (tx, via) -> {
              if (tx.providerTxId().equals("1")) {
                throw new IllegalStateException("boom");
              }
              return recorder.record(tx, via);
            },
            Duration.ofHours(24));

    ReconcileSepayUseCase.Summary summary = flaky.run();

    assertThat(summary.failed()).isEqualTo(1);
    assertThat(summary.matched()).isEqualTo(1);
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  void upstream_failure_propagates_so_the_job_can_log_and_retry() {
    failure = new UpstreamUnavailableException("sepay");

    assertThatThrownBy(useCase::run).isSameAs(failure);
  }
}
