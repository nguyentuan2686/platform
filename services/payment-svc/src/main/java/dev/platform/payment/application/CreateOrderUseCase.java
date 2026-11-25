package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.Provider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.transaction.support.TransactionOperations;

/** Validates the request, prices it from the catalog and opens a PENDING order. */
public class CreateOrderUseCase {

  static final int CODE_ATTEMPTS = 5;
  private static final Set<String> SITES = Set.of("tuvi", "astro");

  private final OrderRepositoryPort orders;
  private final PriceCatalogPort catalog;
  private final PayPalPort paypal;
  private final VietQrSettings vietQr;
  private final Clock clock;
  private final RandomGenerator random;
  private final Duration paymentWindow;
  private final TransactionOperations tx;

  public CreateOrderUseCase(
      OrderRepositoryPort orders,
      PriceCatalogPort catalog,
      PayPalPort paypal,
      VietQrSettings vietQr,
      Clock clock,
      RandomGenerator random,
      Duration paymentWindow,
      TransactionOperations tx) {
    this.orders = orders;
    this.catalog = catalog;
    this.paypal = paypal;
    this.vietQr = vietQr;
    this.clock = clock;
    this.random = random;
    this.paymentWindow = paymentWindow;
    this.tx = tx;
  }

  public CreatedOrder create(CreateOrderCommand command) {
    if (!Boolean.TRUE.equals(command.adultConfirmed())) {
      throw new OrderValidationException("adult_confirmed", "must be true");
    }
    if (command.accountId() == null) {
      throw new OrderValidationException("account_id", "is required");
    }
    if (command.site() == null || !SITES.contains(command.site())) {
      throw new OrderValidationException("site", "unknown site");
    }
    Provider provider = provider(command.provider());
    String expectedCurrency = provider == Provider.SEPAY ? "VND" : "USD";
    if (!expectedCurrency.equals(command.currency())) {
      throw new OrderValidationException(
          "currency", "must be " + expectedCurrency + " for this provider");
    }
    if (command.productCode() == null) {
      throw new OrderValidationException("product_code", "is required");
    }
    Money price =
        catalog
            .price(command.productCode(), command.currency())
            .orElseThrow(() -> new OrderValidationException("product_code", "unknown product"));
    if (provider == Provider.SEPAY && !vietQr.isConfigured()) {
      throw new PaymentNotConfiguredException();
    }

    return tx.execute(status -> open(command, provider, price));
  }

  private CreatedOrder open(CreateOrderCommand command, Provider provider, Money price) {
    Order order = null;
    for (int attempt = 0; attempt < CODE_ATTEMPTS && order == null; attempt++) {
      Order candidate =
          Order.create(
              UUID.randomUUID(),
              OrderCode.generate(command.site(), random),
              command.accountId(),
              command.site(),
              command.productCode(),
              command.chartProfileId(),
              price,
              provider,
              clock.instant(),
              paymentWindow);
      if (orders.insert(candidate)) {
        order = candidate;
      }
    }
    if (order == null) {
      throw new IllegalStateException("could not allocate a unique order code");
    }

    if (provider == Provider.PAYPAL) {
      PayPalCheckout checkout = paypal.createCheckout(order);
      order.attachProviderRef(checkout.paypalOrderId());
      orders.updateProviderRef(order.id(), checkout.paypalOrderId());
      return new CreatedOrder(order, new PaymentInstructions.PayPal(checkout.approveUrl()));
    }
    return new CreatedOrder(order, vietQrInstructions(order));
  }

  private PaymentInstructions vietQrInstructions(Order order) {
    String code = order.code().value();
    String amount = order.amount().amount().stripTrailingZeros().toPlainString();
    String url =
        vietQr
            .imageUrlTemplate()
            .replace("{acc}", encode(vietQr.accountNumber()))
            .replace("{bank}", encode(vietQr.bankCode()))
            .replace("{amount}", amount)
            .replace("{code}", encode(code));
    return new PaymentInstructions.VietQr(
        vietQr.bankCode(), vietQr.accountNumber(), vietQr.accountName(), order.amount(), code, url);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Provider provider(String value) {
    if (value != null) {
      for (Provider p : Provider.values()) {
        if (p.name().equals(value)) {
          return p;
        }
      }
    }
    throw new OrderValidationException("provider", "must be SEPAY or PAYPAL");
  }
}
