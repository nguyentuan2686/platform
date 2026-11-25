package dev.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.support.InMemoryOrders;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class CreateOrderUseCaseTest {

  static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
  static final UUID ACCOUNT = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");

  final InMemoryOrders orders = new InMemoryOrders();
  final Map<String, Money> prices =
      Map.of(
          "CREDIT_5|VND", Money.of("119000", "VND"),
          "CREDIT_1|USD", Money.of("1.19", "USD"));

  final PriceCatalogPort catalog =
      new PriceCatalogPort() {
        @Override
        public Optional<Money> price(String productCode, String currency) {
          return Optional.ofNullable(prices.get(productCode + "|" + currency));
        }

        @Override
        public List<CatalogEntry> list(String currency) {
          return List.of();
        }
      };

  final PayPalPort paypal =
      new PayPalPort() {
        @Override
        public PayPalCheckout createCheckout(Order order) {
          return new PayPalCheckout("FAKE-PP-1", URI.create("https://paypal.fake.invalid/x?t=1"));
        }

        @Override
        public boolean verifyWebhook(Map<String, String> headers, byte[] body) {
          return false;
        }

        @Override
        public String refundCapture(String captureId, Money amount) {
          return "r";
        }
      };

  VietQrSettings qr =
      new VietQrSettings(
          "local-test-bank",
          "0000000000",
          "TEST BANK",
          "https://qr.sepay.vn/img?acc={acc}&bank={bank}&amount={amount}&des={code}");

  CreateOrderUseCase useCase() {
    return useCase(orders);
  }

  CreateOrderUseCase useCase(InMemoryOrders repository) {
    return new CreateOrderUseCase(
        repository,
        catalog,
        paypal,
        qr,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new SecureRandom(),
        Duration.ofMinutes(30),
        TransactionOperations.withoutTransaction());
  }

  static CreateOrderCommand sepay() {
    return new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_5", "VND", "SEPAY", true, null);
  }

  static CreateOrderCommand paypalCommand() {
    return new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_1", "USD", "PAYPAL", true, null);
  }

  @Test
  void vietqr_payload_carries_the_code_and_a_qr_url() {
    CreatedOrder created = useCase().create(sepay());

    String code = created.order().code().value();
    assertThat(created.order().status()).isEqualTo(OrderStatus.PENDING);
    assertThat(created.order().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    assertThat(created.order().provider()).isEqualTo(Provider.SEPAY);
    PaymentInstructions.VietQr payment = (PaymentInstructions.VietQr) created.payment();
    assertThat(payment.content()).isEqualTo(code);
    assertThat(payment.accountNumber()).isEqualTo("0000000000");
    assertThat(payment.qrImageUrl()).contains("amount=119000&des=" + code);
    assertThat(payment.qrImageUrl()).contains("acc=0000000000").contains("bank=local-test-bank");
    assertThat(orders.orders).containsKey(created.order().id());
  }

  @Test
  void paypal_payload_has_the_approve_url_and_stores_the_paypal_order_id() {
    CreatedOrder created = useCase().create(paypalCommand());

    PaymentInstructions.PayPal payment = (PaymentInstructions.PayPal) created.payment();
    assertThat(payment.approveUrl()).hasToString("https://paypal.fake.invalid/x?t=1");
    assertThat(created.order().providerRef()).isEqualTo("FAKE-PP-1");
  }

  @Test
  void code_clash_is_retried_up_to_five_times() {
    orders.failInserts = 4;
    assertThat(useCase().create(sepay()).order()).isNotNull();
    assertThat(orders.insertAttempts).isEqualTo(5);

    InMemoryOrders other = new InMemoryOrders();
    other.failInserts = 5;
    assertThatThrownBy(() -> useCase(other).create(sepay()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(other.insertAttempts).isEqualTo(5);
  }

  @Test
  void adult_confirmation_is_required() {
    for (Boolean value : new Boolean[] {null, false}) {
      assertField(
          new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_5", "VND", "SEPAY", value, null),
          "adult_confirmed");
    }
  }

  @Test
  void currency_must_match_the_provider() {
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_1", "USD", "SEPAY", true, null),
        "currency");
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_5", "VND", "PAYPAL", true, null),
        "currency");
  }

  @Test
  void unknown_product_site_provider_or_missing_account_are_field_errors() {
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", "NOPE", "VND", "SEPAY", true, null),
        "product_code");
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_1", "VND", "SEPAY", true, null),
        "product_code");
    assertField(
        new CreateOrderCommand(ACCOUNT, "mars", "CREDIT_5", "VND", "SEPAY", true, null), "site");
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", "CREDIT_5", "VND", "CASH", true, null), "provider");
    assertField(
        new CreateOrderCommand(null, "tuvi", "CREDIT_5", "VND", "SEPAY", true, null), "account_id");
    assertField(
        new CreateOrderCommand(ACCOUNT, "tuvi", null, "VND", "SEPAY", true, null), "product_code");
  }

  @Test
  void unconfigured_bank_account_makes_vietqr_unavailable() {
    qr = new VietQrSettings("", "", "", "https://qr.example.invalid/{acc}");
    assertThatThrownBy(() -> useCase().create(sepay()))
        .isInstanceOf(PaymentNotConfiguredException.class);
    assertThat(orders.orders).isEmpty();
  }

  private void assertField(CreateOrderCommand command, String field) {
    assertThatThrownBy(() -> useCase().create(command))
        .isInstanceOfSatisfying(
            OrderValidationException.class, e -> assertThat(e.field()).isEqualTo(field));
    assertThat(orders.orders).isEmpty();
  }
}
