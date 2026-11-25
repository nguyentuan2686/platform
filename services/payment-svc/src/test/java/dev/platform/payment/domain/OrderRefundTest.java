package dev.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderRefundTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  static Order paid() {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV234567"),
            UUID.randomUUID(),
            "tuvi",
            "CREDIT_5",
            null,
            Money.of("119000", "VND"),
            Provider.SEPAY,
            T0,
            Duration.ofMinutes(30));
    order.markPaid(Money.of("119000", "VND"), "tx", T0);
    return order;
  }

  @Test
  void paid_order_goes_refund_requested_then_refunded() {
    Order order = paid();
    order.requestRefund();
    assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
    order.completeRefund();
    assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
  }

  @Test
  void only_paid_orders_can_request_a_refund_and_only_requested_ones_complete() {
    Order order = paid();
    assertThatThrownBy(order::completeRefund).isInstanceOf(IllegalStateException.class);
    order.requestRefund();
    assertThatThrownBy(order::requestRefund).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void bank_transaction_to_string_hides_content() {
    BankTransaction tx =
        new BankTransaction(
            Provider.SEPAY, "1", Money.of("1000", "VND"), "secret words TV234567", "ref", T0);
    assertThat(tx.toString()).doesNotContain("secret").contains("redacted");
  }
}
