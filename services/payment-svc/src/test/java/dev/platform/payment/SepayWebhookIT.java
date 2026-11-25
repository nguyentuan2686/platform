package dev.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.payment.application.BankTransactionsPort;
import dev.platform.payment.application.IdentityCreditsPort;
import dev.platform.payment.application.InsufficientCreditsException;
import dev.platform.payment.application.ReconcileSepayUseCase;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.outbox.poll-interval=200ms", "platform.outbox.send-timeout=2s"})
@Import(SepayWebhookIT.Fakes.class)
class SepayWebhookIT extends PaymentIntegrationSupport {

  /** What SePay's API "returns" to the reconciliation job. */
  static class FakeBank implements BankTransactionsPort {
    final List<BankTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public List<BankTransaction> recent(Duration lookback) {
      return List.copyOf(transactions);
    }
  }

  /** identity-svc, which is not part of this test. */
  static class FakeIdentity implements IdentityCreditsPort {
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile RuntimeException failure;

    @Override
    public void reverse(UUID accountId, UUID orderId, int credits) {
      if (failure != null) {
        throw failure;
      }
      calls.add(orderId + "|" + credits);
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    FakeBank fakeBank() {
      return new FakeBank();
    }

    @Bean
    @Primary
    FakeIdentity fakeIdentity() {
      return new FakeIdentity();
    }
  }

  @Autowired FakeBank bank;
  @Autowired FakeIdentity identity;
  @Autowired ReconcileSepayUseCase reconcile;

  @BeforeEach
  void reset() {
    bank.transactions.clear();
    identity.calls.clear();
    identity.failure = null;
  }

  @Test
  void matched_transfer_pays_the_order_and_publishes_one_event() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_5");
    String txId = uniqueTxId();

    HttpResponse<String> response =
        sepayWebhook(
            sepayBody(txId, order.get("code").asString().toLowerCase() + " chuyen tien", "119000"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("success").asBoolean()).isTrue();
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PAID");
    assertThat(events(account)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select match_status from provider_transaction where provider_tx_id = ?",
                String.class,
                txId))
        .isEqualTo("MATCHED");
  }

  @Test
  void duplicate_webhook_is_a_noop() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    String body = sepayBody(txId, order.get("code").asString(), "29000");

    HttpResponse<String> first = sepayWebhook(body);
    HttpResponse<String> second = sepayWebhook(body);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(transactions(txId)).isEqualTo(1);
    assertThat(events(account)).isEqualTo(1);
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_transaction where order_id = ?::uuid",
                Integer.class,
                order.get("order_id").asString()))
        .isEqualTo(1);
  }

  @Test
  void reconcile_after_webhook_is_a_noop() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    sepayWebhook(sepayBody(txId, order.get("code").asString(), "29000"));
    bank.transactions.add(
        new BankTransaction(
            Provider.SEPAY,
            txId,
            Money.of("29000", "VND"),
            order.get("code").asString(),
            "FT" + txId,
            Instant.now()));

    ReconcileSepayUseCase.Summary summary = reconcile.run();

    assertThat(summary.duplicates()).isEqualTo(1);
    assertThat(summary.matched()).isZero();
    assertThat(transactions(txId)).isEqualTo(1);
    assertThat(events(account)).isEqualTo(1);
  }

  @Test
  void reconcile_recovers_a_lost_webhook_and_a_later_webhook_is_a_noop() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    bank.transactions.add(
        new BankTransaction(
            Provider.SEPAY,
            txId,
            Money.of("29000", "VND"),
            "mbvcb." + order.get("code").asString() + "ft2533",
            "FT" + txId,
            Instant.now()));

    assertThat(reconcile.run().matched()).isEqualTo(1);
    sepayWebhook(sepayBody(txId, order.get("code").asString(), "29000"));

    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PAID");
    assertThat(transactions(txId)).isEqualTo(1);
    assertThat(events(account)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select received_via from provider_transaction where provider_tx_id = ?",
                String.class,
                txId))
        .isEqualTo("RECONCILE");
  }

  @Test
  void concurrent_deliveries_of_one_transaction_pay_once() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    String body = sepayBody(txId, order.get("code").asString(), "29000");
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return sepayWebhook(body).statusCode();
                }));
      }
      go.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get()).isEqualTo(200);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(transactions(txId)).isEqualTo(1);
    assertThat(events(account)).isEqualTo(1);
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PAID");
  }

  @Test
  void underpaid_transfer_waits_in_the_queue_and_assigning_it_is_amount_low() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_5");
    String code = order.get("code").asString();
    String txId = uniqueTxId();

    HttpResponse<String> response = sepayWebhook(sepayBody(txId, code + " thieu", "100000"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PENDING");
    assertThat(events(account)).isZero();
    JsonNode queue = JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body());
    JsonNode row = find(queue, txId);
    assertThat(row.get("unmatched_reason").asString()).isEqualTo("AMOUNT_LOW");
    assertThat(row.get("amount").asString()).isEqualTo("100000.00");
    assertThat(row.get("candidate_codes").get(0).asString()).isEqualTo(code);
    assertThat(row.get("content").asString()).contains("thieu");

    HttpResponse<String> assign =
        admin(
            "POST",
            "/admin/unmatched-transactions/" + row.get("id").asLong() + "/assign",
            "{\"order_code\":\"" + code + "\"}");
    assertThat(assign.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(assign.body()).get("reason").asString()).isEqualTo("amount_low");
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PENDING");
  }

  @Test
  void the_operator_can_assign_a_no_code_transfer_then_the_order_is_paid_once() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    sepayWebhook(sepayBody(txId, "chuyen tien an danh", "29000"));
    JsonNode row =
        find(JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body()), txId);
    assertThat(row.get("unmatched_reason").asString()).isEqualTo("NO_CODE");
    String path = "/admin/unmatched-transactions/" + row.get("id").asLong() + "/assign";

    HttpResponse<String> assigned =
        admin("POST", path, "{\"order_code\":\"" + order.get("code").asString() + "\"}");
    HttpResponse<String> again =
        admin("POST", path, "{\"order_code\":\"" + order.get("code").asString() + "\"}");

    assertThat(assigned.statusCode()).isEqualTo(200);
    assertThat(again.statusCode()).isEqualTo(404); // no longer in the queue
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PAID");
    assertThat(events(account)).isEqualTo(1);
    assertThat(
            find(JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body()), txId))
        .isNull();
  }

  @Test
  void resolving_removes_a_transaction_from_the_queue() throws Exception {
    String txId = uniqueTxId();
    sepayWebhook(sepayBody(txId, "no code at all", "5000"));
    JsonNode row =
        find(JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body()), txId);

    HttpResponse<String> bad =
        admin(
            "POST",
            "/admin/unmatched-transactions/" + row.get("id").asLong() + "/resolve",
            "{\"resolution\":\"WHATEVER\"}");
    HttpResponse<String> ok =
        admin(
            "POST",
            "/admin/unmatched-transactions/" + row.get("id").asLong() + "/resolve",
            "{\"resolution\":\"REFUNDED_MANUALLY\",\"note\":\"sent back\"}");

    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(
            find(JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body()), txId))
        .isNull();
  }

  @Test
  void a_second_transfer_to_a_paid_order_is_already_paid_and_still_200() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String code = order.get("code").asString();
    sepayWebhook(sepayBody(uniqueTxId(), code, "29000"));
    String second = uniqueTxId();

    assertThat(sepayWebhook(sepayBody(second, code, "29000")).statusCode()).isEqualTo(200);

    assertThat(
            find(JSON.readTree(admin("GET", "/admin/unmatched-transactions", null).body()), second)
                .get("unmatched_reason")
                .asString())
        .isEqualTo("ALREADY_PAID");
    assertThat(events(account)).isEqualTo(1);
  }

  @Test
  void webhook_authentication_and_parsing() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String txId = uniqueTxId();
    String body = sepayBody(txId, order.get("code").asString(), "29000");

    HttpResponse<String> none = send("POST", "/webhooks/sepay", body, Map.of());
    HttpResponse<String> wrong =
        send("POST", "/webhooks/sepay", body, Map.of("Authorization", "Apikey wrong"));
    HttpResponse<String> malformed =
        send(
            "POST",
            "/webhooks/sepay",
            "{not json",
            Map.of("Authorization", "Apikey " + SEPAY_SECRET));
    HttpResponse<String> outgoing =
        sepayWebhook(body.replace("\"transferType\":\"in\"", "\"transferType\":\"out\""));

    assertThat(none.statusCode()).isEqualTo(401);
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(none.body()).doesNotContain(SEPAY_SECRET).doesNotContain("Apikey");
    assertThat(malformed.statusCode()).isEqualTo(400);
    assertThat(outgoing.statusCode()).isEqualTo(200);
    assertThat(transactions(txId)).isZero();
    assertThat(orderStatus(order.get("order_id").asString())).isEqualTo("PENDING");
  }

  @Test
  void admin_endpoints_need_the_admin_token_and_ignore_the_internal_one() throws Exception {
    assertThat(send("GET", "/admin/unmatched-transactions", null, Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(
            send("GET", "/admin/unmatched-transactions", null, Map.of("X-Admin-Token", "wrong"))
                .statusCode())
        .isEqualTo(401);
    assertThat(
            send("GET", "/admin/refund-requests", null, Map.of("X-Internal-Token", TOKEN))
                .statusCode())
        .isEqualTo(401);
    assertThat(admin("GET", "/admin/refund-requests?status=OPEN", null).statusCode())
        .isEqualTo(200);
    // the regular API still needs the internal token
    assertThat(send("GET", "/orders?account_id=" + UUID.randomUUID(), null, Map.of()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void refund_of_a_paid_credit_pack_reverses_credits_and_waits_for_the_operator() throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_5");
    String orderId = order.get("order_id").asString();
    sepayWebhook(sepayBody(uniqueTxId(), order.get("code").asString(), "119000"));
    String body = "{\"account_id\":\"" + account + "\"}";

    HttpResponse<String> first = internal("POST", "/orders/" + orderId + "/refund-requests", body);
    HttpResponse<String> repeat = internal("POST", "/orders/" + orderId + "/refund-requests", body);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(repeat.statusCode()).isEqualTo(200);
    JsonNode request = JSON.readTree(first.body());
    assertThat(request.get("status").asString()).isEqualTo("OPEN");
    assertThat(JSON.readTree(repeat.body()).get("id")).isEqualTo(request.get("id"));
    assertThat(identity.calls).contains(orderId + "|5");
    assertThat(orderStatus(orderId)).isEqualTo("REFUND_REQUESTED");

    JsonNode open = JSON.readTree(admin("GET", "/admin/refund-requests?status=OPEN", null).body());
    assertThat(open.toString()).contains(request.get("id").asString());

    HttpResponse<String> done =
        admin(
            "POST",
            "/admin/refund-requests/" + request.get("id").asString() + "/complete",
            "{\"note\":\"sent\"}");

    assertThat(done.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(done.body()).get("status").asString()).isEqualTo("DONE");
    assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
  }

  @Test
  void refund_is_refused_for_used_credits_passes_and_unpaid_orders_and_reports_identity_down()
      throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode pack = createOrder(account, "CREDIT_5");
    JsonNode pass = createOrder(account, "PASS_MONTH");
    sepayWebhook(sepayBody(uniqueTxId(), pack.get("code").asString(), "119000"));
    sepayWebhook(sepayBody(uniqueTxId(), pass.get("code").asString(), "49000"));
    String body = "{\"account_id\":\"" + account + "\"}";
    String packPath = "/orders/" + pack.get("order_id").asString() + "/refund-requests";

    identity.failure = new InsufficientCreditsException();
    HttpResponse<String> used = internal("POST", packPath, body);
    identity.failure = new dev.platform.payment.application.UpstreamUnavailableException("x");
    HttpResponse<String> down = internal("POST", packPath, body);
    identity.failure = null;
    HttpResponse<String> passRefund =
        internal("POST", "/orders/" + pass.get("order_id").asString() + "/refund-requests", body);
    HttpResponse<String> unpaid =
        internal(
            "POST",
            "/orders/"
                + createOrder(account, "CREDIT_1").get("order_id").asString()
                + "/refund-requests",
            body);
    HttpResponse<String> stranger =
        internal("POST", packPath, "{\"account_id\":\"" + UUID.randomUUID() + "\"}");

    assertThat(used.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(used.body()).get("reason").asString()).isEqualTo("credits_used");
    assertThat(down.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(down.body()).get("type").asString())
        .isEqualTo("urn:problem:upstream-unavailable");
    assertThat(passRefund.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(passRefund.body()).get("reason").asString())
        .isEqualTo("not_refundable");
    assertThat(unpaid.statusCode()).isEqualTo(409);
    assertThat(stranger.statusCode()).isEqualTo(404);
    assertThat(orderStatus(pack.get("order_id").asString())).isEqualTo("PAID");
  }

  @Test
  void paypal_capture_pays_the_order_and_a_refund_goes_out_at_once() throws Exception {
    UUID account = UUID.randomUUID();
    String create =
        "{\"account_id\":\"%s\",\"site\":\"tuvi\",\"product_code\":\"CREDIT_1\",\"currency\":\"USD\",\"provider\":\"PAYPAL\",\"adult_confirmed\":true}"
            .formatted(account);
    JsonNode order = JSON.readTree(internal("POST", "/orders", create).body());
    String orderId = order.get("order_id").asString();
    String paypalOrder =
        jdbc.queryForObject(
            "select provider_ref from orders where id = ?::uuid", String.class, orderId);
    String capture = "CAP-" + uniqueTxId();
    String event =
        "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"%s\",\"amount\":{\"value\":\"1.19\",\"currency_code\":\"USD\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\"%s\"}}}}"
            .formatted(capture, paypalOrder);
    Map<String, String> signed = Map.of("X-Fake-PayPal-Signature", hmacHex(PAYPAL_SECRET, event));

    HttpResponse<String> bad =
        send("POST", "/webhooks/paypal", event, Map.of("X-Fake-PayPal-Signature", "00"));
    HttpResponse<String> ok = send("POST", "/webhooks/paypal", event, signed);
    HttpResponse<String> again = send("POST", "/webhooks/paypal", event, signed);
    HttpResponse<String> ignored =
        send(
            "POST",
            "/webhooks/paypal",
            "{\"event_type\":\"CHECKOUT.ORDER.APPROVED\"}",
            Map.of(
                "X-Fake-PayPal-Signature",
                hmacHex(PAYPAL_SECRET, "{\"event_type\":\"CHECKOUT.ORDER.APPROVED\"}")));

    assertThat(bad.statusCode()).isEqualTo(401);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(ignored.statusCode()).isEqualTo(200);
    assertThat(orderStatus(orderId)).isEqualTo("PAID");
    assertThat(transactions(capture)).isEqualTo(1);
    assertThat(events(account)).isEqualTo(1);

    HttpResponse<String> refund =
        internal(
            "POST",
            "/orders/" + orderId + "/refund-requests",
            "{\"account_id\":\"" + account + "\"}");

    assertThat(refund.statusCode()).isEqualTo(201);
    JsonNode request = JSON.readTree(refund.body());
    assertThat(request.get("status").asString()).isEqualTo("DONE");
    assertThat(request.get("provider_refund_id").asString()).startsWith("FAKE-PP-REFUND-");
    assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
  }

  /** The queue row of a provider transaction, or null when it is not in the queue. */
  private JsonNode find(JsonNode queue, String txId) {
    List<Long> ids =
        jdbc.queryForList(
            "select id from provider_transaction where provider_tx_id = ?", Long.class, txId);
    for (JsonNode row : queue) {
      if (!ids.isEmpty() && row.get("id").asLong() == ids.get(0)) {
        return row;
      }
    }
    return null;
  }
}
