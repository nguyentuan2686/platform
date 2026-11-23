package dev.platform.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.locations=classpath:db/migration,classpath:db/platform",
      // Fast relay + bounded producer waits so the broker-down test finishes quickly.
      "platform.outbox.poll-interval=200ms",
      "platform.outbox.send-timeout=2s",
      "spring.kafka.producer.properties.max.block.ms=3000",
      "spring.kafka.producer.properties.request.timeout.ms=1000",
      "spring.kafka.producer.properties.delivery.timeout.ms=2000"
    })
class OrderFlowIT {

  static final String TOPIC = "orders.order-placed.v1";

  // Singletons shared by every test (weak dev machine); removed by Ryuk at JVM exit.
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

  static final JsonMapper JSON = JsonMapper.shared();
  static final String BODY =
      "{\"customerId\":\"c-42\",\"items\":["
          + "{\"sku\":\"sku-1\",\"quantity\":2,\"unitPrice\":10.00},"
          + "{\"sku\":\"sku-2\",\"quantity\":1,\"unitPrice\":5.50}]}";

  @LocalServerPort int port;

  @Value("${spring.kafka.bootstrap-servers}")
  String bootstrap;

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

  @Test
  void post_then_get_returns_the_order_and_event_is_published() throws Exception {
    HttpResponse<String> created = post(BODY);
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode order = JSON.readTree(created.body());
    String id = order.get("id").asString();
    assertThat(created.headers().firstValue("Location")).hasValue("/orders/" + id);
    assertThat(order.get("total").decimalValue()).isEqualByComparingTo("25.50");

    HttpResponse<String> fetched = get("/orders/" + id);
    assertThat(fetched.statusCode()).isEqualTo(200);
    JsonNode got = JSON.readTree(fetched.body());
    assertThat(got).isEqualTo(order);

    ConsumerRecord<String, String> record = awaitRecord(id, Duration.ofSeconds(5));
    assertThat(record.key()).isEqualTo(id);
    JsonNode event = JSON.readTree(record.value());
    assertThat(ContractSupport.eventSchema().validate(event)).isEmpty();
    assertThat(event.get("event_type").asString()).isEqualTo("OrderPlaced");
    assertThat(event.get("data").get("order_id").asString()).isEqualTo(id);
    assertThat(event.get("data").get("customer_id").asString()).isEqualTo("c-42");
    assertThat(event.get("data").get("total").asString()).isEqualTo("25.50");
    assertThat(event.get("data").get("item_count").asInt()).isEqualTo(3);

    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
  }

  @Test
  void unknown_order_is_404_problem() throws Exception {
    HttpResponse<String> response = get("/orders/" + UUID.randomUUID());
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).has("trace_id")).isTrue();
  }

  @Test
  void post_succeeds_when_broker_down() throws Exception {
    var docker = REDPANDA.getDockerClient();
    docker.pauseContainerCmd(REDPANDA.getContainerId()).exec();
    String id;
    try {
      HttpResponse<String> created = post(BODY);
      assertThat(created.statusCode()).isEqualTo(201);
      id = JSON.readTree(created.body()).get("id").asString();
      assertThat(get("/orders/" + id).statusCode()).isEqualTo(200);
      Thread.sleep(1500); // let the relay attempt (and fail) at least once while the broker is down
    } finally {
      docker.unpauseContainerCmd(REDPANDA.getContainerId()).exec();
    }

    ConsumerRecord<String, String> record = awaitRecord(id, Duration.ofSeconds(10));
    assertThat(ContractSupport.eventSchema().validate(JSON.readTree(record.value()))).isEmpty();
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

  HttpResponse<String> post(String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
