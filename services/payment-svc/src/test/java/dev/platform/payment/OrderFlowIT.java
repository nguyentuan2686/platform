package dev.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.payment.application.MarkPaidUseCase;
import dev.platform.payment.domain.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.outbox.poll-interval=200ms", "platform.outbox.send-timeout=2s"})
class OrderFlowIT {

  static final String TOPIC = "payments.payment-succeeded.v1";
  static final String TOKEN = "local-test-internal-token";
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
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired MarkPaidUseCase markPaid;

  final HttpClient http = HttpClient.newHttpClient();
  KafkaConsumer<String, String> consumer;

  @BeforeAll
  static void createTopic() throws Exception {
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", REDPANDA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
        throw e;
      }
    }
  }

  @BeforeEach
  void subscribe() {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(p);
    consumer.subscribe(List.of(TOPIC));
  }

  @AfterEach
  void close() {
    consumer.close(Duration.ofSeconds(1));
  }

  static String orderBody(UUID account, String product, String currency, String provider) {
    return "{\"account_id\":\"%s\",\"site\":\"tuvi\",\"product_code\":\"%s\",\"currency\":\"%s\",\"provider\":\"%s\",\"adult_confirmed\":true}"
        .formatted(account, product, currency, provider);
  }

  @Test
  void create_then_mark_paid_publishes_one_payment_succeeded_event() throws Exception {
    UUID account = UUID.randomUUID();

    HttpResponse<String> created = post("/orders", orderBody(account, "CREDIT_5", "VND", "SEPAY"));

    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode order = JSON.readTree(created.body());
    String id = order.get("order_id").asString();
    String code = order.get("code").asString();
    assertThat(code).matches("^TV[2-9A-HJKMNP-Z]{6}$");
    assertThat(order.get("amount").asString()).isEqualTo("119000.00");
    assertThat(order.get("payment").get("kind").asString()).isEqualTo("VIETQR");
    assertThat(order.get("payment").get("content").asString()).isEqualTo(code);
    assertThat(order.get("payment").get("qr_image_url").asString())
        .contains("amount=119000&des=" + code);

    JsonNode fetched = JSON.readTree(get("/orders/" + id + "?account_id=" + account).body());
    assertThat(fetched.get("status").asString()).isEqualTo("PENDING");

    markPaid.markPaid(UUID.fromString(id), Money.of("119000", "VND"), "tx-1", Instant.now());

    JsonNode paid = JSON.readTree(get("/orders/" + id + "?account_id=" + account).body());
    assertThat(paid.get("status").asString()).isEqualTo("PAID");
    ConsumerRecord<String, String> record = awaitRecord(account.toString(), Duration.ofSeconds(10));
    JsonNode event = JSON.readTree(record.value());
    assertThat(ContractSupport.eventSchema().validate(event)).isEmpty();
    assertThat(event.get("event_type").asString()).isEqualTo("PaymentSucceeded");
    JsonNode data = event.get("data");
    assertThat(data.get("order_id").asString()).isEqualTo(id);
    assertThat(data.get("order_code").asString()).isEqualTo(code);
    assertThat(data.get("account_id").asString()).isEqualTo(account.toString());
    assertThat(data.get("product_code").asString()).isEqualTo("CREDIT_5");
    assertThat(data.get("amount").asString()).isEqualTo("119000.00");
    assertThat(data.get("provider").asString()).isEqualTo("SEPAY");

    assertThatThrownBy(
            () ->
                markPaid.markPaid(
                    UUID.fromString(id), Money.of("119000", "VND"), "tx-2", Instant.now()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where msg_key = ?",
                Integer.class,
                account.toString()))
        .isEqualTo(1);
  }

  @Test
  void paypal_order_gets_fake_checkout_and_other_accounts_cannot_read_it() throws Exception {
    UUID account = UUID.randomUUID();

    JsonNode order =
        JSON.readTree(post("/orders", orderBody(account, "CREDIT_1", "USD", "PAYPAL")).body());

    String id = order.get("order_id").asString();
    assertThat(order.get("payment").get("kind").asString()).isEqualTo("PAYPAL");
    assertThat(order.get("payment").get("approve_url").asString())
        .startsWith("https://paypal.fake.invalid/checkoutnow?token=FAKE-PP-");
    assertThat(
            jdbc.queryForObject(
                "select provider_ref from orders where id = ?::uuid", String.class, id))
        .startsWith("FAKE-PP-");
    assertThat(get("/orders/" + id + "?account_id=" + UUID.randomUUID()).statusCode())
        .isEqualTo(404);
    assertThat(JSON.readTree(get("/orders?account_id=" + account).body())).hasSize(1);
  }

  @Test
  void validation_catalog_and_auth() throws Exception {
    UUID account = UUID.randomUUID();

    HttpResponse<String> wrongCurrency =
        post("/orders", orderBody(account, "CREDIT_1", "USD", "SEPAY"));
    assertThat(wrongCurrency.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(wrongCurrency.body()).get("errors").get(0).get("field").asString())
        .isEqualTo("currency");

    HttpResponse<String> products = get("/products?site=tuvi&currency=VND");
    assertThat(products.statusCode()).isEqualTo(200);
    JsonNode list = JSON.readTree(products.body());
    assertThat(list).hasSize(4);
    assertThat(list.toString()).contains("\"code\":\"PASS_YEAR\"").contains("399000.00");

    HttpResponse<String> noToken =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/products?site=tuvi&currency=VND"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(noToken.statusCode()).isEqualTo(401);
    assertThat(
            http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health/ready"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(200);
  }

  ConsumerRecord<String, String> awaitRecord(String key, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
        if (key.equals(r.key())) {
          return r;
        }
      }
    }
    throw new AssertionError("no record with key " + key + " within " + timeout);
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", TOKEN)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Internal-Token", TOKEN)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
