package dev.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import dev.platform.order.domain.OrderLine;
import dev.platform.order.domain.OrderPlaced;
import dev.platform.starter.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

  static class FakeRepo implements OrderRepositoryPort {
    final Map<OrderId, Order> store = new HashMap<>();

    @Override
    public void save(Order order) {
      store.put(order.id(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.ofNullable(store.get(id));
    }
  }

  static class FakeEvents implements OrderEventsPort {
    final List<OrderPlaced> published = new ArrayList<>();

    @Override
    public void publish(OrderPlaced event) {
      published.add(event);
    }
  }

  private final FakeRepo repo = new FakeRepo();
  private final FakeEvents events = new FakeEvents();
  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final OrderService service =
      new OrderService(repo, events, Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void placeSavesAndPublishesOnce() {
    Order o =
        service.place(
            new PlaceOrderCommand("c1", List.of(new OrderLine("A", 2, new BigDecimal("1.25")))));
    assertThat(repo.store).containsKey(o.id());
    assertThat(events.published).hasSize(1);
    assertThat(events.published.get(0).orderId()).isEqualTo(o.id());
    assertThat(o.createdAt()).isEqualTo(now);
    assertThat(service.get(o.id())).isSameAs(o);
  }

  @Test
  void getUnknownThrowsNotFound() {
    assertThatThrownBy(() -> service.get(OrderId.newId())).isInstanceOf(NotFoundException.class);
  }
}
