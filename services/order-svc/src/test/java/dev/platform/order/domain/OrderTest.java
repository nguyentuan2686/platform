package dev.platform.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static List<OrderLine> lines() {
    return List.of(
        new OrderLine("A", 2, new BigDecimal("10.00")),
        new OrderLine("B", 1, new BigDecimal("5.50")));
  }

  @Test
  void computesTotalAndStatus() {
    Order o = Order.place(OrderId.newId(), "c1", lines(), NOW);
    assertThat(o.total()).isEqualByComparingTo("99.99");
    assertThat(o.total().scale()).isEqualTo(2);
    assertThat(o.status()).isEqualTo(OrderStatus.PLACED);
    assertThat(o.createdAt()).isEqualTo(NOW);
    assertThat(o.customerId()).isEqualTo("c1");
  }

  @Test
  void rejectsEmptyLines() {
    assertThatThrownBy(() -> Order.place(OrderId.newId(), "c1", List.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullLines() {
    assertThatThrownBy(() -> Order.place(OrderId.newId(), "c1", null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankCustomer() {
    assertThatThrownBy(() -> Order.place(OrderId.newId(), " ", lines(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(OrderId.newId(), null, lines(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidLines() {
    assertThatThrownBy(() -> new OrderLine("A", 0, BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderLine("A", 1, new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderLine(" ", 1, BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderLine("A", 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emitsExactlyOneOrderPlaced() {
    OrderId id = OrderId.newId();
    Order o = Order.place(id, "c1", lines(), NOW);
    List<OrderPlaced> events = o.pullEvents();
    assertThat(events).hasSize(1);
    OrderPlaced e = events.get(0);
    assertThat(e.orderId()).isEqualTo(id);
    assertThat(e.customerId()).isEqualTo("c1");
    assertThat(e.itemCount()).isEqualTo(3);
    assertThat(e.total()).isEqualByComparingTo("25.50");
    assertThat(e.occurredAt()).isEqualTo(NOW);
    assertThat(o.pullEvents()).isEmpty();
  }

  @Test
  void restoreEmitsNoEventsAndLinesAreUnmodifiable() {
    Order o = Order.restore(OrderId.newId(), "c1", lines(), OrderStatus.PLACED, NOW);
    assertThat(o.pullEvents()).isEmpty();
    assertThat(o.total()).isEqualByComparingTo("25.50");
    assertThatThrownBy(() -> o.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void orderIdParsing() {
    OrderId id = OrderId.newId();
    assertThat(OrderId.of(id.value().toString())).isEqualTo(id);
    assertThatThrownBy(() -> OrderId.of("nope")).isInstanceOf(IllegalArgumentException.class);
  }
}
