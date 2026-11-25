package dev.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.UnmatchedReason;
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.RecordingEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class PayPalWebhookUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  final InMemoryOrders orders = new InMemoryOrders();
  final InMemoryProviderTransactions txs = new InMemoryProviderTransactions();
  final RecordingEvents events = new RecordingEvents();
  boolean signatureValid = true;

  final PayPalPort paypal =
      new PayPalPort() {
        @Override
        public PayPalCheckout createCheckout(Order order) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyWebhook(Map<String, String> headers, byte[] body) {
          return signatureValid;
        }

        @Override
        public String refundCapture(String captureId, Money amount) {
          throw new UnsupportedOperationException();
        }
      };

  final PayPalWebhookUseCase useCase =
      new PayPalWebhookUseCase(
          paypal,
          new RecordBankTransactionUseCase(
              orders, txs, events, TransactionOperations.withoutTransaction()));

  Order paypalOrder() {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV234567"),
            UUID.randomUUID(),
            "tuvi",
            "CREDIT_1",
            null,
            Money.of("1.19", "USD"),
            Provider.PAYPAL,
            T0,
            Duration.ofMinutes(30));
    order.attachProviderRef("FAKE-PP-1");
    orders.save(order);
    return order;
  }

  @Test
  void verification_is_delegated_to_the_port() {
    assertThat(useCase.verify(Map.of(), new byte[0])).isTrue();
    signatureValid = false;
    assertThat(useCase.verify(Map.of(), new byte[0])).isFalse();
  }

  @Test
  void completed_capture_with_the_exact_amount_pays_the_order() {
    Order order = paypalOrder();

    MatchOutcome outcome =
        useCase.captureCompleted(
            new PayPalCapture("CAP-1", "FAKE-PP-1", Money.of("1.19", "USD"), T0));

    assertThat(outcome).isEqualTo(new MatchOutcome.Matched(order.id()));
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(events.events).hasSize(1);
    assertThat(txs.matchedTransactionId(order.id(), Provider.PAYPAL)).contains("CAP-1");
  }

  @Test
  void a_different_amount_is_amount_low_even_when_higher() {
    Order order = paypalOrder();

    assertThat(
            useCase.captureCompleted(
                new PayPalCapture("CAP-1", "FAKE-PP-1", Money.of("5.00", "USD"), T0)))
        .isEqualTo(new MatchOutcome.Unmatched(UnmatchedReason.AMOUNT_LOW));
    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void unknown_paypal_order_is_unknown_code() {
    assertThat(
            useCase.captureCompleted(
                new PayPalCapture("CAP-1", "FAKE-PP-404", Money.of("1.19", "USD"), T0)))
        .isEqualTo(new MatchOutcome.Unmatched(UnmatchedReason.UNKNOWN_CODE));
  }

  @Test
  void redelivered_capture_is_a_duplicate() {
    paypalOrder();
    PayPalCapture capture = new PayPalCapture("CAP-1", "FAKE-PP-1", Money.of("1.19", "USD"), T0);
    useCase.captureCompleted(capture);

    assertThat(useCase.captureCompleted(capture)).isEqualTo(new MatchOutcome.Duplicate());
    assertThat(events.events).hasSize(1);
  }
}
