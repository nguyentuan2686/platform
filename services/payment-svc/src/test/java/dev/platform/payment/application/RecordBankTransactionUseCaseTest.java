package dev.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.UnmatchedReason;
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.RecordingEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class RecordBankTransactionUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  final InMemoryOrders orders = new InMemoryOrders();
  final InMemoryProviderTransactions txs = new InMemoryProviderTransactions();
  final RecordingEvents events = new RecordingEvents();
  final RecordBankTransactionUseCase useCase =
      new RecordBankTransactionUseCase(
          orders, txs, events, TransactionOperations.withoutTransaction());

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

  static BankTransaction bank(String id, String amount, String content, Instant at) {
    return new BankTransaction(Provider.SEPAY, id, Money.of(amount, "VND"), content, "FT1", at);
  }

  @Test
  void exact_payment_with_the_code_marks_the_order_paid_and_publishes_once() {
    Order order = order("TV8K2QAB");

    MatchOutcome outcome =
        useCase.record(bank("1", "119000", "TV8K2QAB chuyen tien", T0), ReceivedVia.WEBHOOK);

    assertThat(outcome).isEqualTo(new MatchOutcome.Matched(order.id()));
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(events.events).hasSize(1);
    assertThat(txs.rows.get(1L).status()).isEqualTo("MATCHED");
  }

  @Test
  void lowercase_code_glued_to_bank_text_still_matches() {
    Order order = order("TV8K2QAB");

    MatchOutcome outcome =
        useCase.record(
            bank("1", "119000", "mbvcb.123.tv8k2qabft25330 nguyen van a", T0), ReceivedVia.WEBHOOK);

    assertThat(outcome).isEqualTo(new MatchOutcome.Matched(order.id()));
  }

  @Test
  void payment_three_hours_after_the_window_still_matches() {
    Order order = order("TV8K2QAB");

    MatchOutcome outcome =
        useCase.record(
            bank("1", "119000", "TV8K2QAB", T0.plus(Duration.ofHours(3))), ReceivedVia.WEBHOOK);

    assertThat(outcome).isEqualTo(new MatchOutcome.Matched(order.id()));
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  void overpayment_pays_the_order_and_keeps_a_note() {
    Order order = order("TV8K2QAB");

    MatchOutcome outcome = useCase.record(bank("1", "150000", "TV8K2QAB", T0), ReceivedVia.WEBHOOK);

    assertThat(outcome).isInstanceOf(MatchOutcome.Matched.class);
    assertThat(order.note()).isEqualTo("overpaid_by=31000.00");
  }

  @Test
  void no_code_goes_to_the_unmatched_queue() {
    order("TV8K2QAB");

    MatchOutcome outcome = useCase.record(bank("1", "119000", "chuyen tien"), ReceivedVia.WEBHOOK);

    assertUnmatched(outcome, UnmatchedReason.NO_CODE);
    assertThat(events.events).isEmpty();
    assertThat(txs.listUnmatched()).hasSize(1);
  }

  @Test
  void a_code_of_no_known_order_is_unknown_code() {
    order("TV8K2QAB");

    assertUnmatched(
        useCase.record(bank("1", "119000", "TV9M3RCD"), ReceivedVia.WEBHOOK),
        UnmatchedReason.UNKNOWN_CODE);
  }

  @Test
  void two_known_codes_are_ambiguous() {
    order("TV8K2QAB");
    order("TV9M3RCD");

    assertUnmatched(
        useCase.record(bank("1", "119000", "TV8K2QAB TV9M3RCD"), ReceivedVia.WEBHOOK),
        UnmatchedReason.AMBIGUOUS);
    assertThat(events.events).isEmpty();
  }

  @Test
  void one_known_and_one_unknown_code_matches_the_known_one() {
    Order order = order("TV8K2QAB");

    assertThat(useCase.record(bank("1", "119000", "TV8K2QAB TV9M3RCD"), ReceivedVia.WEBHOOK))
        .isEqualTo(new MatchOutcome.Matched(order.id()));
  }

  @Test
  void underpayment_is_amount_low_and_leaves_the_order_pending() {
    Order order = order("TV8K2QAB");

    MatchOutcome outcome = useCase.record(bank("1", "100000", "TV8K2QAB"), ReceivedVia.WEBHOOK);

    assertUnmatched(outcome, UnmatchedReason.AMOUNT_LOW);
    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(events.events).isEmpty();
    assertThat(txs.rows.get(1L).orderId()).isEqualTo(order.id());
  }

  @Test
  void another_currency_is_amount_low() {
    order("TV8K2QAB");
    BankTransaction usd =
        new BankTransaction(Provider.SEPAY, "1", Money.of("200", "USD"), "TV8K2QAB", "r", T0);

    assertUnmatched(useCase.record(usd, ReceivedVia.WEBHOOK), UnmatchedReason.AMOUNT_LOW);
  }

  @Test
  void second_payment_to_a_paid_order_is_already_paid() {
    Order order = order("TV8K2QAB");
    useCase.record(bank("1", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK);

    MatchOutcome second = useCase.record(bank("2", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK);

    assertUnmatched(second, UnmatchedReason.ALREADY_PAID);
    assertThat(events.events).hasSize(1);
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  void refunded_order_is_already_paid_too() {
    Order order = order("TV8K2QAB");
    useCase.record(bank("1", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK);
    order.requestRefund();

    assertUnmatched(
        useCase.record(bank("2", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK),
        UnmatchedReason.ALREADY_PAID);
  }

  @Test
  void the_same_provider_transaction_twice_is_a_duplicate_noop() {
    order("TV8K2QAB");
    useCase.record(bank("1", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK);

    MatchOutcome again = useCase.record(bank("1", "119000", "TV8K2QAB"), ReceivedVia.RECONCILE);

    assertThat(again).isEqualTo(new MatchOutcome.Duplicate());
    assertThat(events.events).hasSize(1);
    assertThat(txs.rows).hasSize(1);
  }

  @Test
  void the_same_id_under_another_provider_is_not_a_duplicate() {
    order("TV8K2QAB");
    useCase.record(bank("1", "119000", "TV8K2QAB"), ReceivedVia.WEBHOOK);

    MatchOutcome other =
        useCase.record(
            new BankTransaction(Provider.PAYPAL, "1", Money.of("1", "USD"), null, null, T0),
            ReceivedVia.WEBHOOK);

    assertThat(other).isNotEqualTo(new MatchOutcome.Duplicate());
  }

  private static BankTransaction bank(String id, String amount, String content) {
    return bank(id, amount, content, T0);
  }

  private static void assertUnmatched(MatchOutcome outcome, UnmatchedReason reason) {
    assertThat(outcome).isEqualTo(new MatchOutcome.Unmatched(reason));
  }
}
