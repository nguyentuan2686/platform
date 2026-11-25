package dev.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.PaymentSucceeded;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class OrderQueriesTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final UUID ACCOUNT = UUID.randomUUID();

  final InMemoryOrders orders = new InMemoryOrders();

  Order order(UUID account, String code, Instant at) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            new OrderCode(code),
            account,
            "tuvi",
            "CREDIT_5",
            null,
            Money.of("119000", "VND"),
            Provider.SEPAY,
            at,
            Duration.ofMinutes(30));
    orders.insert(o);
    return o;
  }

  GetOrderUseCase get(Instant now) {
    return new GetOrderUseCase(orders, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void get_returns_own_order_with_view_status_and_hides_other_accounts() {
    Order mine = order(ACCOUNT, "TV222222", T0);

    OrderView view = get(T0.plus(Duration.ofMinutes(31))).get(mine.id(), ACCOUNT);

    assertThat(view.status()).isEqualTo("EXPIRED");
    assertThatThrownBy(() -> get(T0).get(mine.id(), UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> get(T0).get(UUID.randomUUID(), ACCOUNT))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void list_is_newest_first_capped_at_fifty() {
    List<Order> all = new ArrayList<>();
    for (int i = 0; i < 55; i++) {
      all.add(order(ACCOUNT, "TV%06d".formatted(i), T0.plusSeconds(i)));
    }
    order(UUID.randomUUID(), "TV999999", T0);

    List<OrderView> views = get(T0).list(ACCOUNT);

    assertThat(views).hasSize(50);
    assertThat(views.getFirst().order().id()).isEqualTo(all.get(54).id());
  }

  @Test
  void mark_paid_persists_and_publishes_once() {
    Order o = order(ACCOUNT, "TV222222", T0);
    List<PaymentSucceeded> published = new ArrayList<>();
    MarkPaidUseCase useCase =
        new MarkPaidUseCase(orders, published::add, TransactionOperations.withoutTransaction());

    useCase.markPaid(o.id(), Money.of("120000", "VND"), "tx-1", T0.plusSeconds(5));

    assertThat(published).hasSize(1);
    assertThat(published.getFirst().orderCode()).isEqualTo("TV222222");
    assertThat(orders.orders.get(o.id()).note()).isEqualTo("overpaid_by=1000.00");
    assertThatThrownBy(() -> useCase.markPaid(o.id(), Money.of("119000", "VND"), "tx-2", T0))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> useCase.markPaid(UUID.randomUUID(), Money.of("119000", "VND"), "tx", T0))
        .isInstanceOf(NotFoundException.class);
    assertThat(published).hasSize(1);
  }

  @Test
  void catalog_lists_active_prices_and_validates_filters() {
    PriceCatalogPort port =
        new PriceCatalogPort() {
          @Override
          public Optional<Money> price(String productCode, String currency) {
            return Optional.empty();
          }

          @Override
          public List<CatalogEntry> list(String currency) {
            return List.of(new CatalogEntry("CREDIT_1", Money.of("29000", currency)));
          }
        };
    CatalogUseCase catalog = new CatalogUseCase(port);

    assertThat(catalog.list("tuvi", "VND")).hasSize(1);
    assertThatThrownBy(() -> catalog.list("mars", "VND"))
        .isInstanceOfSatisfying(
            OrderValidationException.class, e -> assertThat(e.field()).isEqualTo("site"));
    assertThatThrownBy(() -> catalog.list("tuvi", "EUR"))
        .isInstanceOfSatisfying(
            OrderValidationException.class, e -> assertThat(e.field()).isEqualTo("currency"));
  }
}
