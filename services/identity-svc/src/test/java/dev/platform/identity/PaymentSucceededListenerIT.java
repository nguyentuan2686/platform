package dev.platform.identity;

import static dev.platform.identity.support.HttpIdentity.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.platform.identity.support.HttpIdentity;
import dev.platform.identity.support.HttpIdentity.Login;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The PaymentSucceeded consumer against real Postgres and Redpanda. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.kafka.listener.auto-startup=true")
@Import(PaymentSucceededListenerIT.RecordingMailer.class)
class PaymentSucceededListenerIT {

  static final String TOPIC = "payments.payment-succeeded.v1";
  static final String DLT = TOPIC + ".DLT";

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
  }

  @TestConfiguration
  static class RecordingMailer {
    @Bean
    @Primary
    dev.platform.identity.application.MailerPort recordingMailer() {
      return (to, link, lang) -> HttpIdentity.LINKS.add(link);
    }
  }

  static final JsonMapper JSON = JsonMapper.shared();
  static final Schema SCHEMA =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(readSchema());

  static String readSchema() {
    try {
      return Files.readString(
          Path.of("..", "..", "contracts", "events", "payment-succeeded.v1.schema.json"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  HttpIdentity api;
  KafkaProducer<String, String> producer;

  @BeforeAll
  static void createTopics() throws Exception {
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", REDPANDA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
          .all()
          .get();
    }
  }

  @BeforeEach
  void setUp() {
    api = new HttpIdentity(port);
    Properties p = new Properties();
    p.put("bootstrap.servers", REDPANDA.getBootstrapServers());
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
  }

  @AfterAll
  static void noop() {}

  private static ObjectNode event(UUID eventId, UUID orderId, UUID accountId, String product) {
    ObjectNode root = JSON.createObjectNode();
    root.put("event_id", eventId.toString());
    root.put("event_type", "PaymentSucceeded");
    root.put("occurred_at", Instant.now().toString());
    ObjectNode data = root.putObject("data");
    data.put("order_id", orderId.toString());
    data.put("order_code", "TVABC123");
    data.put("account_id", accountId.toString());
    data.put("product_code", product);
    data.put("site", "tuvi");
    data.put("amount", "119000.00");
    data.put("currency", "VND");
    data.put("provider", "SEPAY");
    data.put("paid_at", Instant.now().toString());
    data.putNull("chart_profile_id");
    data.put("future_field", "ignored"); // additive v1 change: consumers ignore unknown fields
    return root;
  }

  private void publish(ObjectNode event) throws Exception {
    assertThat(SCHEMA.validate(event)).isEmpty();
    send(event.toString(), event.get("data").get("account_id").asString());
  }

  private void send(String payload, String key) throws Exception {
    producer.send(new ProducerRecord<>(TOPIC, key, payload)).get();
    producer.flush();
  }

  private int credits(Login login) throws Exception {
    return api.credits(login);
  }

  private void awaitCredits(Login login, int expected) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(credits(login)).isEqualTo(expected));
  }

  private Login account() throws Exception {
    return api.login("pay-" + UUID.randomUUID() + "@example.org");
  }

  @Test
  void credit_pack_is_granted_once_per_event_and_per_order() throws Exception {
    Login login = account();
    assertThat(credits(login)).isEqualTo(1); // signup bonus
    UUID event = UUID.randomUUID();
    UUID order = UUID.randomUUID();

    publish(event(event, order, login.accountId(), "CREDIT_5"));
    awaitCredits(login, 6);

    publish(event(event, order, login.accountId(), "CREDIT_5")); // same event again
    // A marker event proves the duplicates were consumed before we assert "unchanged".
    UUID marker = UUID.randomUUID();
    publish(event(UUID.randomUUID(), marker, login.accountId(), "CREDIT_1"));
    awaitCredits(login, 7);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from credit_ledger where reason = 'purchase' and ref = ?",
                Integer.class,
                order.toString()))
        .isEqualTo(1);
  }

  @Test
  void same_order_new_event_id_grants_once() throws Exception {
    Login login = account();
    UUID order = UUID.randomUUID();

    publish(event(UUID.randomUUID(), order, login.accountId(), "CREDIT_5"));
    awaitCredits(login, 6);
    publish(event(UUID.randomUUID(), order, login.accountId(), "CREDIT_5"));
    publish(event(UUID.randomUUID(), UUID.randomUUID(), login.accountId(), "CREDIT_1"));

    awaitCredits(login, 7);
    assertThat(jdbc.queryForObject("select count(*) from processed_event", Integer.class))
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void month_pass_shows_in_entitlements_with_two_questions() throws Exception {
    Login login = account();

    publish(event(UUID.randomUUID(), UUID.randomUUID(), login.accountId(), "PASS_MONTH"));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              JsonNode view = json(api.send("GET", "/me/entitlements", login.token(), null));
              assertThat(view.get("pass").isNull()).isFalse();
              assertThat(view.get("pass").get("kind").asString()).isEqualTo("MONTH");
              assertThat(view.get("pass").get("questions_left").asInt()).isEqualTo(2);
              assertThat(view.get("features").get(0).asString()).isEqualTo("daily_detail");
            });
  }

  @Test
  void unknown_product_unknown_account_and_bad_envelope_go_to_the_dead_letter_topic()
      throws Exception {
    Login login = account();
    UUID badProductOrder = UUID.randomUUID();
    UUID unknownAccountOrder = UUID.randomUUID();
    ObjectNode badProduct =
        event(UUID.randomUUID(), badProductOrder, login.accountId(), "CREDIT_9");
    ObjectNode unknownAccount =
        event(UUID.randomUUID(), unknownAccountOrder, UUID.randomUUID(), "CREDIT_1");
    String garbage = "{\"not\":\"an envelope " + UUID.randomUUID() + "\"}";

    send(badProduct.toString(), login.accountId().toString());
    send(unknownAccount.toString(), "k");
    send(garbage, "k");

    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    java.util.Set<String> seen = new java.util.HashSet<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
      consumer.subscribe(List.of(DLT));
      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(
              () -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                  seen.add(r.value());
                }
                assertThat(seen)
                    .anyMatch(v -> v.contains(badProductOrder.toString()))
                    .anyMatch(v -> v.contains(unknownAccountOrder.toString()))
                    .anyMatch(v -> v.contains("an envelope"));
              });
    }

    assertThat(credits(login)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from credit_ledger where ref in (?, ?)",
                Integer.class,
                badProductOrder.toString(),
                unknownAccountOrder.toString()))
        .isZero();
  }
}
