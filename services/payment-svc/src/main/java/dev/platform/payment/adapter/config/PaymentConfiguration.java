package dev.platform.payment.adapter.config;

import dev.platform.payment.adapter.out.messaging.OutboxPaymentEventsAdapter;
import dev.platform.payment.adapter.out.paypal.FakePayPalAdapter;
import dev.platform.payment.adapter.out.persistence.JdbcOrderRepository;
import dev.platform.payment.adapter.out.persistence.JdbcPriceCatalog;
import dev.platform.payment.application.CatalogUseCase;
import dev.platform.payment.application.CreateOrderUseCase;
import dev.platform.payment.application.GetOrderUseCase;
import dev.platform.payment.application.MarkPaidUseCase;
import dev.platform.payment.application.OrderRepositoryPort;
import dev.platform.payment.application.PayPalPort;
import dev.platform.payment.application.PaymentEventsPort;
import dev.platform.payment.application.PriceCatalogPort;
import dev.platform.payment.application.VietQrSettings;
import dev.platform.starter.outbox.OutboxWriter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/** Wires the framework-free application services to their adapters. */
@Configuration(proxyBeanMethods = false)
class PaymentConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }

  @Bean
  OrderRepositoryPort orderRepository(JdbcClient jdbc) {
    return new JdbcOrderRepository(jdbc);
  }

  @Bean
  PriceCatalogPort priceCatalog(JdbcClient jdbc) {
    return new JdbcPriceCatalog(jdbc);
  }

  @Bean
  PaymentEventsPort paymentEvents(OutboxWriter outbox, JsonMapper json) {
    return new OutboxPaymentEventsAdapter(outbox, json);
  }

  /** Only the fake adapter exists until F3; any other mode fails startup. */
  @Bean
  PayPalPort payPalPort(
      @Value("${payment.paypal.mode:fake}") String mode,
      @Value("${payment.paypal.fake-webhook-secret:}") String webhookSecret) {
    if (!"fake".equals(mode)) {
      throw new IllegalStateException("real PayPal adapter lands in F3");
    }
    return new FakePayPalAdapter(webhookSecret);
  }

  @Bean
  VietQrSettings vietQrSettings(
      @Value("${payment.sepay.bank-code:}") String bankCode,
      @Value("${payment.sepay.account-number:}") String accountNumber,
      @Value("${payment.sepay.account-name:}") String accountName,
      @Value("${payment.vietqr.image-url-template}") String template) {
    return new VietQrSettings(bankCode, accountNumber, accountName, template);
  }

  @Bean
  CreateOrderUseCase createOrderUseCase(
      OrderRepositoryPort orders,
      PriceCatalogPort catalog,
      PayPalPort paypal,
      VietQrSettings vietQr,
      Clock clock,
      SecureRandom random,
      @Value("${payment.order.payment-window:PT30M}") Duration window,
      TransactionOperations tx) {
    return new CreateOrderUseCase(orders, catalog, paypal, vietQr, clock, random, window, tx);
  }

  @Bean
  GetOrderUseCase getOrderUseCase(OrderRepositoryPort orders, Clock clock) {
    return new GetOrderUseCase(orders, clock);
  }

  @Bean
  CatalogUseCase catalogUseCase(PriceCatalogPort catalog) {
    return new CatalogUseCase(catalog);
  }

  @Bean
  MarkPaidUseCase markPaidUseCase(
      OrderRepositoryPort orders, PaymentEventsPort events, TransactionOperations tx) {
    return new MarkPaidUseCase(orders, events, tx);
  }
}
