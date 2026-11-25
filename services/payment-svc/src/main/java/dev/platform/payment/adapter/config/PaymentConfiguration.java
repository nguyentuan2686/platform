package dev.platform.payment.adapter.config;

import dev.platform.payment.adapter.in.schedule.SepayReconcileJob;
import dev.platform.payment.adapter.in.web.AdminTokenFilter;
import dev.platform.payment.adapter.in.web.SepayWebhookAuthenticator;
import dev.platform.payment.adapter.out.identity.IdentityCreditsAdapter;
import dev.platform.payment.adapter.out.messaging.OutboxPaymentEventsAdapter;
import dev.platform.payment.adapter.out.paypal.FakePayPalAdapter;
import dev.platform.payment.adapter.out.persistence.JdbcOrderRepository;
import dev.platform.payment.adapter.out.persistence.JdbcPriceCatalog;
import dev.platform.payment.adapter.out.persistence.JdbcProviderTransactions;
import dev.platform.payment.adapter.out.persistence.JdbcRefundRequests;
import dev.platform.payment.adapter.out.sepay.SepayApiAdapter;
import dev.platform.payment.application.AdminUseCase;
import dev.platform.payment.application.BankTransactionsPort;
import dev.platform.payment.application.CatalogUseCase;
import dev.platform.payment.application.CreateOrderUseCase;
import dev.platform.payment.application.GetOrderUseCase;
import dev.platform.payment.application.IdentityCreditsPort;
import dev.platform.payment.application.MarkPaidUseCase;
import dev.platform.payment.application.OrderRepositoryPort;
import dev.platform.payment.application.PayPalPort;
import dev.platform.payment.application.PayPalWebhookUseCase;
import dev.platform.payment.application.PaymentEventsPort;
import dev.platform.payment.application.PriceCatalogPort;
import dev.platform.payment.application.ProviderTransactionPort;
import dev.platform.payment.application.ReconcileSepayUseCase;
import dev.platform.payment.application.RecordBankTransactionUseCase;
import dev.platform.payment.application.RefundRequestPort;
import dev.platform.payment.application.RefundUseCase;
import dev.platform.payment.application.VietQrSettings;
import dev.platform.starter.outbox.OutboxWriter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.servlet.HandlerExceptionResolver;
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

  @Bean
  ProviderTransactionPort providerTransactions(JdbcClient jdbc) {
    return new JdbcProviderTransactions(jdbc);
  }

  @Bean
  RefundRequestPort refundRequests(JdbcClient jdbc) {
    return new JdbcRefundRequests(jdbc);
  }

  @Bean
  IdentityCreditsPort identityCredits(
      @Value("${payment.identity.url:http://localhost:8084}") String url,
      @Value("${platform.internal-token:}") String internalToken) {
    return new IdentityCreditsAdapter(url, internalToken);
  }

  @Bean
  BankTransactionsPort bankTransactions(
      @Value("${payment.sepay.api-base-url:https://my.sepay.vn}") String baseUrl,
      @Value("${payment.sepay.api-token:}") String apiToken,
      @Value("${payment.sepay.account-number:}") String accountNumber,
      Clock clock) {
    return new SepayApiAdapter(SepayApiAdapter.client(baseUrl), accountNumber, apiToken, clock);
  }

  @Bean
  SepayWebhookAuthenticator sepayWebhookAuthenticator(
      @Value("${payment.sepay.webhook-auth:apikey}") String mode,
      @Value("${payment.sepay.webhook-secret:}") String secret) {
    return new SepayWebhookAuthenticator(mode, secret);
  }

  @Bean
  RecordBankTransactionUseCase recordBankTransactionUseCase(
      OrderRepositoryPort orders,
      ProviderTransactionPort transactions,
      PaymentEventsPort events,
      TransactionOperations tx) {
    return new RecordBankTransactionUseCase(orders, transactions, events, tx);
  }

  @Bean
  PayPalWebhookUseCase payPalWebhookUseCase(
      PayPalPort paypal, RecordBankTransactionUseCase recorder) {
    return new PayPalWebhookUseCase(paypal, recorder);
  }

  @Bean
  ReconcileSepayUseCase reconcileSepayUseCase(
      BankTransactionsPort bank,
      RecordBankTransactionUseCase recorder,
      @Value("${payment.sepay.reconcile.lookback:PT24H}") Duration lookback) {
    return new ReconcileSepayUseCase(bank, recorder, lookback);
  }

  @Bean
  @ConditionalOnProperty(name = "payment.sepay.reconcile.enabled", havingValue = "true")
  SepayReconcileJob sepayReconcileJob(
      ReconcileSepayUseCase reconcile,
      @Value("${payment.sepay.reconcile.interval:PT5M}") Duration interval) {
    return new SepayReconcileJob(reconcile, interval);
  }

  @Bean
  AdminUseCase adminUseCase(
      OrderRepositoryPort orders,
      ProviderTransactionPort transactions,
      RefundRequestPort refunds,
      PaymentEventsPort events,
      Clock clock,
      TransactionOperations tx) {
    return new AdminUseCase(orders, transactions, refunds, events, clock, tx);
  }

  @Bean
  RefundUseCase refundUseCase(
      OrderRepositoryPort orders,
      PriceCatalogPort catalog,
      IdentityCreditsPort identity,
      RefundRequestPort refunds,
      PayPalPort paypal,
      ProviderTransactionPort transactions,
      Clock clock,
      TransactionOperations tx) {
    return new RefundUseCase(orders, catalog, identity, refunds, paypal, transactions, clock, tx);
  }

  /** Closed (always 401) while ADMIN_API_TOKEN is empty. */
  @Bean
  AdminTokenFilter adminTokenFilter(
      @Value("${payment.admin.token:}") String token,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
    return new AdminTokenFilter(token, resolver);
  }
}
