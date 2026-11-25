package dev.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final UUID ACCOUNT = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");

  static Order pending() {
    return Order.create(
        UUID.randomUUID(),
        new OrderCode("TV234567"),
        ACCOUNT,
        "tuvi",
        "CREDIT_5",
        null,
        Money.of("119000", "VND"),
        Provider.SEPAY,
        T0,
        Duration.ofMinutes(30));
  }

  @Test
  void new_order_is_pending_and_expires_after_the_window() {
    Order order = pending();
    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    assertThat(order.amount().toPlainString()).isEqualTo("119000.00");
  }

  @Test
  void exact_amount_marks_paid_without_note() {
    Order order = pending();

    PaymentSucceeded event = order.markPaid(Money.of("119000", "VND"), "tx-1", T0.plusSeconds(60));

    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(order.note()).isNull();
    assertThat(order.providerRef()).isEqualTo("tx-1");
    assertThat(order.paidAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(event.orderId()).isEqualTo(order.id());
    assertThat(event.orderCode()).isEqualTo("TV234567");
    assertThat(event.accountId()).isEqualTo(ACCOUNT);
    assertThat(event.productCode()).isEqualTo("CREDIT_5");
    assertThat(event.amount()).isEqualTo(Money.of("119000", "VND"));
    assertThat(event.provider()).isEqualTo(Provider.SEPAY);
    assertThat(event.paidAt()).isEqualTo(T0.plusSeconds(60));
  }

  @Test
  void overpayment_is_noted() {
    Order order = pending();

    order.markPaid(Money.of("120000", "VND"), "tx-1", T0);

    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(order.note()).isEqualTo("overpaid_by=1000.00");
  }

  @Test
  void underpayment_and_currency_mismatch_are_rejected_and_leave_the_order_pending() {
    Order order = pending();

    assertThatThrownBy(() -> order.markPaid(Money.of("118999", "VND"), "tx", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> order.markPaid(Money.of("119000", "USD"), "tx", T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void second_mark_paid_fails() {
    Order order = pending();
    order.markPaid(Money.of("119000", "VND"), "tx-1", T0);

    assertThatThrownBy(() -> order.markPaid(Money.of("119000", "VND"), "tx-2", T0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void paypal_order_keeps_its_paypal_order_id_as_provider_ref() {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV234567"),
            ACCOUNT,
            "tuvi",
            "CREDIT_1",
            null,
            Money.of("1.19", "USD"),
            Provider.PAYPAL,
            T0,
            Duration.ofMinutes(30));
    order.attachProviderRef("FAKE-PP-1");

    order.markPaid(Money.of("1.19", "USD"), "capture-1", T0);

    assertThat(order.providerRef()).isEqualTo("FAKE-PP-1");
  }

  @Test
  void view_status_is_expired_only_for_pending_orders_past_expiry() {
    Order order = pending();
    assertThat(order.viewStatus(T0.plus(Duration.ofMinutes(29)))).isEqualTo("PENDING");
    assertThat(order.viewStatus(T0.plus(Duration.ofMinutes(31)))).isEqualTo("EXPIRED");

    order.markPaid(Money.of("119000", "VND"), "tx", T0.plus(Duration.ofMinutes(31)));
    assertThat(order.viewStatus(T0.plus(Duration.ofMinutes(40)))).isEqualTo("PAID");
  }

  @Test
  void money_normalizes_scale_and_compares() {
    assertThat(Money.of("29000", "VND")).isEqualTo(Money.of("29000.00", "VND"));
    assertThat(Money.of("1.19", "USD").toString()).contains("1.19");
    assertThatThrownBy(() -> Money.of("1.001", "USD")).isInstanceOf(ArithmeticException.class);
  }
}
