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
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.InMemoryRefundRequests;
import dev.platform.starter.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class RefundUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final UUID ACCOUNT = UUID.randomUUID();

  final InMemoryOrders orders = new InMemoryOrders();
  final InMemoryRefundRequests refunds = new InMemoryRefundRequests();
  final InMemoryProviderTransactions txs = new InMemoryProviderTransactions();
  final List<String> reversals = new ArrayList<>();
  final List<String> paypalRefunds = new ArrayList<>();
  RuntimeException identityFailure;
  RuntimeException paypalFailure;

  final PriceCatalogPort catalog =
      new PriceCatalogPort() {
        @Override
        public Optional<Money> price(String productCode, String currency) {
          return Optional.empty();
        }

        @Override
        public List<CatalogEntry> list(String currency) {
          return List.of();
        }

        @Override
        public Optional<Integer> refundableCredits(String productCode) {
          return Optional.ofNullable(Map.of("CREDIT_5", 5, "CREDIT_1", 1).get(productCode));
        }
      };

  final IdentityCreditsPort identity =
      (accountId, orderId, credits) -> {
        if (identityFailure != null) {
          throw identityFailure;
        }
        reversals.add(accountId + "|" + orderId + "|" + credits);
      };

  final PayPalPort paypal =
      new PayPalPort() {
        @Override
        public PayPalCheckout createCheckout(Order order) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyWebhook(Map<String, String> headers, byte[] body) {
          return false;
        }

        @Override
        public String refundCapture(String captureId, Money amount) {
          if (paypalFailure != null) {
            throw paypalFailure;
          }
          paypalRefunds.add(captureId + "|" + amount.toPlainString());
          return "REFUND-1";
        }
      };

  final RefundUseCase useCase =
      new RefundUseCase(
          orders,
          catalog,
          identity,
          refunds,
          paypal,
          txs,
          Clock.fixed(T0, ZoneOffset.UTC),
          TransactionOperations.withoutTransaction());

  Order paidOrder(String product, Provider provider, String amount, String currency) {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV8K2QAB"),
            ACCOUNT,
            "tuvi",
            product,
            null,
            Money.of(amount, currency),
            provider,
            T0,
            Duration.ofMinutes(30));
    order.markPaid(Money.of(amount, currency), "ref", T0);
    orders.save(order);
    return order;
  }

  @Test
  void a_pass_order_is_not_refundable_and_identity_is_not_called() {
    Order order = paidOrder("PASS_MONTH", Provider.SEPAY, "49000", "VND");

    assertThatThrownBy(() -> useCase.request(order.id(), ACCOUNT))
        .isInstanceOfSatisfying(
            ConflictException.class, e -> assertThat(e.reason()).isEqualTo("not_refundable"));
    assertThat(reversals).isEmpty();
  }

  @Test
  void a_pending_order_is_not_refundable() {
    Order order =
        Order.create(
            UUID.randomUUID(),
            new OrderCode("TV8K2QAB"),
            ACCOUNT,
            "tuvi",
            "CREDIT_5",
            null,
            Money.of("119000", "VND"),
            Provider.SEPAY,
            T0,
            Duration.ofMinutes(30));
    orders.save(order);

    assertThatThrownBy(() -> useCase.request(order.id(), ACCOUNT))
        .isInstanceOfSatisfying(
            ConflictException.class, e -> assertThat(e.reason()).isEqualTo("not_refundable"));
  }

  @Test
  void another_accounts_order_is_not_found() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");

    assertThatThrownBy(() -> useCase.request(order.id(), UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void used_credits_refuse_the_refund_and_leave_the_order_paid() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");
    identityFailure = new InsufficientCreditsException();

    assertThatThrownBy(() -> useCase.request(order.id(), ACCOUNT))
        .isInstanceOfSatisfying(
            ConflictException.class, e -> assertThat(e.reason()).isEqualTo("credits_used"));
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    assertThat(refunds.requests).isEmpty();
  }

  @Test
  void identity_down_is_an_upstream_failure_and_changes_nothing() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");
    identityFailure = new UpstreamUnavailableException("identity-svc");

    assertThatThrownBy(() -> useCase.request(order.id(), ACCOUNT))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  void sepay_refund_reverses_the_credits_and_stays_open_for_a_manual_transfer() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");

    RefundUseCase.Result result = useCase.request(order.id(), ACCOUNT);

    assertThat(result.created()).isTrue();
    assertThat(result.request().status()).isEqualTo(RefundStatus.OPEN);
    assertThat(result.request().amount()).isEqualTo(Money.of("119000", "VND"));
    assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
    assertThat(reversals).containsExactly(ACCOUNT + "|" + order.id() + "|5");
    assertThat(paypalRefunds).isEmpty();
  }

  @Test
  void paypal_refund_is_sent_at_once_and_done() {
    Order order = paidOrder("CREDIT_1", Provider.PAYPAL, "1.19", "USD");
    txs.insertIfAbsent(
        new BankTransaction(Provider.PAYPAL, "CAP-1", Money.of("1.19", "USD"), null, null, T0),
        ReceivedVia.WEBHOOK);
    txs.markMatched(1L, order.id());

    RefundUseCase.Result result = useCase.request(order.id(), ACCOUNT);

    assertThat(result.request().status()).isEqualTo(RefundStatus.DONE);
    assertThat(result.request().providerRefundId()).isEqualTo("REFUND-1");
    assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(paypalRefunds).containsExactly("CAP-1|1.19");
  }

  @Test
  void a_failed_paypal_refund_stays_open_and_a_retry_completes_it() {
    Order order = paidOrder("CREDIT_1", Provider.PAYPAL, "1.19", "USD");
    txs.insertIfAbsent(
        new BankTransaction(Provider.PAYPAL, "CAP-1", Money.of("1.19", "USD"), null, null, T0),
        ReceivedVia.WEBHOOK);
    txs.markMatched(1L, order.id());
    paypalFailure = new UpstreamUnavailableException("paypal");

    assertThatThrownBy(() -> useCase.request(order.id(), ACCOUNT))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThat(refunds.findByOrder(order.id()).orElseThrow().status()).isEqualTo(RefundStatus.OPEN);

    paypalFailure = null;
    RefundUseCase.Result retry = useCase.request(order.id(), ACCOUNT);

    assertThat(retry.created()).isFalse();
    assertThat(retry.request().status()).isEqualTo(RefundStatus.DONE);
    assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
  }

  @Test
  void repeating_the_call_returns_the_same_request() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");
    RefundRequest first = useCase.request(order.id(), ACCOUNT).request();

    RefundUseCase.Result again = useCase.request(order.id(), ACCOUNT);

    assertThat(again.created()).isFalse();
    assertThat(again.request().id()).isEqualTo(first.id());
    assertThat(refunds.requests).hasSize(1);
  }

  @Test
  void a_crash_between_reversal_and_request_is_safe_to_retry() {
    Order order = paidOrder("CREDIT_5", Provider.SEPAY, "119000", "VND");
    // identity already reversed (first attempt crashed afterwards); the second reversal is a no-op
    reversals.add("earlier");

    RefundUseCase.Result result = useCase.request(order.id(), ACCOUNT);

    assertThat(result.request().status()).isEqualTo(RefundStatus.OPEN);
    assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
  }
}
