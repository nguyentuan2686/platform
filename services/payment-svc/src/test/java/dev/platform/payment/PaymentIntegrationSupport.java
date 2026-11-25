package dev.platform.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared containers and HTTP helpers for the webhook integration tests (one JVM, one pair). */
abstract class PaymentIntegrationSupport {

  static final String TOKEN = "local-test-internal-token";
  static final String ADMIN = "local-test-admin-token";
  static final String SEPAY_SECRET = "local-test-sepay-secret";
  static final String PAYPAL_SECRET = "local-test-paypal-webhook-secret";
  static final JsonMapper JSON = JsonMapper.shared();

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.1")
          .withCommand("redpanda", "start", "--mode=dev-container", "--smp=1", "--memory=512M");

  static {
    Startables.deepStart(POSTGRES, REDPANDA).join();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    registry.add("platform.internal-token", () -> TOKEN);
    registry.add("platform.internal-token.open-paths", () -> "/health/**,/webhooks/**,/admin/**");
    registry.add("payment.sepay.webhook-secret", () -> SEPAY_SECRET);
    registry.add("payment.admin.token", () -> ADMIN);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
    headers.forEach(request::header);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> internal(String method, String path, String body) throws Exception {
    return send(method, path, body, Map.of("X-Internal-Token", TOKEN));
  }

  HttpResponse<String> admin(String method, String path, String body) throws Exception {
    return send(method, path, body, Map.of("X-Admin-Token", ADMIN));
  }

  /** Creates a SEPAY order and returns the response (order_id, code, ...). */
  JsonNode createOrder(UUID account, String product) throws Exception {
    String body =
        "{\"account_id\":\"%s\",\"site\":\"tuvi\",\"product_code\":\"%s\",\"currency\":\"VND\",\"provider\":\"SEPAY\",\"adult_confirmed\":true}"
            .formatted(account, product);
    HttpResponse<String> response = internal("POST", "/orders", body);
    if (response.statusCode() != 201) {
      throw new AssertionError("order not created: " + response.statusCode());
    }
    return JSON.readTree(response.body());
  }

  static String sepayBody(String txId, String content, String amount) {
    return "{\"id\":%s,\"gateway\":\"TestBank\",\"transactionDate\":\"2026-11-25 17:00:00\",\"accountNumber\":\"0000000000\",\"code\":null,\"content\":\"%s\",\"transferType\":\"in\",\"transferAmount\":%s,\"accumulated\":0,\"subAccount\":null,\"referenceCode\":\"FT%s\",\"description\":\"x\"}"
        .formatted(txId, content, amount, txId);
  }

  HttpResponse<String> sepayWebhook(String body) throws Exception {
    return send("POST", "/webhooks/sepay", body, Map.of("Authorization", "Apikey " + SEPAY_SECRET));
  }

  static String hmacHex(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  String orderStatus(String orderId) {
    return jdbc.queryForObject(
        "select status from orders where id = ?::uuid", String.class, orderId);
  }

  int events(UUID account) {
    return jdbc.queryForObject(
        "select count(*) from outbox_event where msg_key = ?", Integer.class, account.toString());
  }

  int transactions(String txId) {
    return jdbc.queryForObject(
        "select count(*) from provider_transaction where provider_tx_id = ?", Integer.class, txId);
  }

  static String uniqueTxId() {
    return Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
  }
}
