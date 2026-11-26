package dev.platform.starter.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = OutboxRelayIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      // The scheduled relay must not race the explicit publishPending() calls in these tests.
      "platform.outbox.poll-interval=1h",
      // Not tight: a send the broker acks after the client gave up is re-sent by the next cycle,
      // a duplicate the exact-count assertions below would read as a relay bug. 8 s of ack latency
      // does not happen even on a loaded 2-core runner; broker_down pays the timeout once.
      "platform.outbox.send-timeout=8s",
      "spring.kafka.producer.properties.max.block.ms=10000",
      "spring.kafka.producer.properties.request.timeout.ms=3000",
      "spring.kafka.producer.properties.delivery.timeout.ms=8000"
    })
class OutboxRelayIT {

  static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  static final String SPAN_ID = "b7ad6b7169203331";
  static final Duration PUBLISH_DEADLINE = Duration.ofSeconds(50);

  // Shared by all test methods (weak dev machine): started once, removed by Ryuk at JVM exit.
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.1")
          .withCommand("redpanda", "start", "--mode=dev-container", "--smp=1", "--memory=512M");

  static {
    Startables.deepStart(POSTGRES, REDPANDA).join();
    // API-only OpenTelemetry with the W3C propagator; production gets it from the OTel agent.
    GlobalOpenTelemetry.set(
        OpenTelemetry.propagating(
            ContextPropagators.create(W3CTraceContextPropagator.getInstance())));
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {}

  @Autowired OutboxWriter writer;
  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired DataSource dataSource;
  @Autowired KafkaTemplate<?, ?> kafkaTemplate;
  @Autowired JsonMapper jsonMapper;
  @Autowired OutboxProperties properties;

  TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(txManager);
    jdbc.update("delete from outbox_event");
  }

  @Test
  void publishes_envelope_with_headers() throws InterruptedException {
    String topic = topic();
    Instant occurredAt = Instant.parse("2026-11-23T10:15:30.123456Z");
    OutboxEvent event = event(topic, occurredAt);
    var spanContext =
        SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    try (Scope ignored = Context.root().with(Span.wrap(spanContext)).makeCurrent()) {
      tx.executeWithoutResult(status -> writer.append(event));
    }
    String storedTraceparent =
        jdbc.queryForObject(
            "select traceparent from outbox_event where event_id = ?",
            String.class,
            event.eventId());
    assertThat(storedTraceparent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");

    // Outside the send scope (here: between send and mark) the cycle's bookkeeping SQL runs under
    // the relay's non-sampled parent, not the restored request trace.
    SpanContext[] afterSend = new SpanContext[1];
    relay.beforeMark = () -> afterSend[0] = Span.current().getSpanContext();
    try {
      assertThat(publishAll(relay)).isEqualTo(1);
    } finally {
      relay.beforeMark = () -> {};
    }
    assertThat(afterSend[0].isValid()).isTrue();
    assertThat(afterSend[0].isSampled()).isFalse();
    assertThat(afterSend[0].getTraceId()).isNotEqualTo(TRACE_ID);

    List<ConsumerRecord<String, String>> records = consume(topic);
    assertThat(records).hasSize(1);
    ConsumerRecord<String, String> record = records.get(0);
    assertThat(record.key()).isEqualTo("order-1");
    JsonNode envelope = jsonMapper.readTree(record.value());
    assertThat(envelope.propertyNames())
        .containsExactlyInAnyOrder("event_id", "event_type", "occurred_at", "data");
    assertThat(envelope.get("event_id").asString()).isEqualTo(event.eventId().toString());
    assertThat(envelope.get("event_type").asString()).isEqualTo("OrderPlaced");
    assertThat(envelope.get("occurred_at").asString()).isEqualTo("2026-11-23T10:15:30.123456Z");
    assertThat(envelope.get("data")).isEqualTo(event.data());
    assertThat(header(record, "event_id")).isEqualTo(event.eventId().toString());
    assertThat(header(record, "event_type")).isEqualTo("OrderPlaced");
    assertThat(header(record, "traceparent")).isEqualTo(storedTraceparent);
    assertThat(publishedAt(event.eventId())).isNotNull();
  }

  @Test
  void does_not_republish_published_rows() throws InterruptedException {
    String topic = topic();
    OutboxEvent event = event(topic, Instant.now());
    tx.executeWithoutResult(status -> writer.append(event));
    assertThat(publishAll(relay)).isEqualTo(1);

    assertThat(relay.publishPending()).isZero();
    assertThat(consume(topic)).hasSize(1);
  }

  @Test
  void crash_before_mark_resends_same_event_id() throws InterruptedException {
    String topic = topic();
    OutboxEvent event = event(topic, Instant.now());
    tx.executeWithoutResult(status -> writer.append(event));
    // No span was current at append time, so no traceparent is stored.
    assertThat(
            jdbc.queryForObject(
                "select traceparent from outbox_event where event_id = ?",
                String.class,
                event.eventId()))
        .isNull();
    boolean[] crashed = {false};
    relay.beforeMark =
        () -> {
          if (!crashed[0]) {
            crashed[0] = true;
            throw new IllegalStateException("simulated crash after send, before mark");
          }
        };
    try {
      // The crash fires only after a successful send; a send that times out on a loaded machine
      // publishes 0 without reaching it, so cycle until the crash happened (deadline-bound).
      long deadline = System.nanoTime() + PUBLISH_DEADLINE.toNanos();
      while (!crashed[0] && System.nanoTime() < deadline) {
        try {
          assertThat(relay.publishPending()).isZero();
          Thread.sleep(200);
        } catch (IllegalStateException expected) {
          assertThat(expected).hasMessageContaining("simulated crash");
        }
      }
      assertThat(crashed[0]).isTrue();
      assertThat(publishedAt(event.eventId())).isNull();

      assertThat(publishAll(relay)).isEqualTo(1);
    } finally {
      relay.beforeMark = () -> {};
    }

    List<ConsumerRecord<String, String>> records = consume(topic);
    assertThat(records).hasSize(2);
    assertThat(records)
        .extracting(r -> header(r, "event_id"))
        .containsOnly(event.eventId().toString());
    assertThat(publishedAt(event.eventId())).isNotNull();
  }

  @Test
  void concurrent_relays_do_not_double_send() throws Exception {
    String topic = topic();
    tx.executeWithoutResult(
        status -> {
          for (int i = 0; i < 200; i++) {
            writer.append(event(topic, Instant.now()));
          }
        });
    // Small batches so both relays really interleave over the 200 rows.
    var small = new OutboxProperties(true, properties.pollInterval(), 20, properties.sendTimeout());
    var relayA = new OutboxRelay(dataSource, txManager, kafkaTemplate, jsonMapper, small);
    var relayB = new OutboxRelay(dataSource, txManager, kafkaTemplate, jsonMapper, small);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (OutboxRelay r : List.of(relayA, relayB)) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return publishAll(r);
                }));
      }
      start.countDown();
      int published = 0;
      for (Future<Integer> f : results) {
        published += f.get(60, TimeUnit.SECONDS);
      }
      assertThat(published).isEqualTo(200);
    } finally {
      pool.shutdownNow();
      relayA.close();
      relayB.close();
    }

    List<ConsumerRecord<String, String>> records = consume(topic);
    assertThat(records).hasSize(200);
    Set<String> ids = records.stream().map(r -> header(r, "event_id")).collect(Collectors.toSet());
    assertThat(ids).hasSize(200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where published_at is null", Integer.class))
        .isZero();
  }

  @Test
  void broker_down_keeps_rows_and_does_not_throw() throws InterruptedException {
    String topic = topic();
    // Warm up the producer (metadata for the topic) so the failure is a real delivery timeout.
    OutboxEvent warmup = event(topic, Instant.now());
    tx.executeWithoutResult(status -> writer.append(warmup));
    assertThat(publishAll(relay)).isEqualTo(1);

    OutboxEvent event = event(topic, Instant.now());
    tx.executeWithoutResult(status -> writer.append(event));
    var docker = REDPANDA.getDockerClient();
    docker.pauseContainerCmd(REDPANDA.getContainerId()).exec();
    try {
      int[] published = {-1};
      assertThatNoException().isThrownBy(() -> published[0] = relay.publishPending());
      assertThat(published[0]).isZero();
      assertThat(publishedAt(event.eventId())).isNull();
      assertThat(
              jdbc.queryForObject(
                  "select attempts from outbox_event where event_id = ?",
                  Integer.class,
                  event.eventId()))
          .isEqualTo(1);
    } finally {
      docker.unpauseContainerCmd(REDPANDA.getContainerId()).exec();
    }

    // The producer reconnects (backoff, metadata refresh, a new idempotence epoch after the expired
    // batch) on its own schedule: the end state is "published", not "the next cycle sent it".
    assertThat(publishAll(relay)).isEqualTo(1);
    assertThat(publishedAt(event.eventId())).isNotNull();
    assertThat(consume(topic))
        .extracting(r -> header(r, "event_id"))
        .contains(event.eventId().toString());
  }

  @Test
  void append_outside_transaction_fails() {
    assertThatThrownBy(() -> writer.append(event(topic(), Instant.now())))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
  }

  // --- helpers ---

  private OutboxEvent event(String topic, Instant occurredAt) {
    JsonNode data = jsonMapper.createObjectNode().put("order_id", "order-1").put("total", 42);
    return new OutboxEvent(UUID.randomUUID(), "OrderPlaced", topic, "order-1", occurredAt, data);
  }

  /**
   * A fresh topic, created up front (1 partition) and waited on until it has a leader, so no test
   * pays for broker-side auto-creation inside a send timeout.
   */
  private static String topic() {
    String name = "outbox-it-" + UUID.randomUUID();
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (true) {
        try {
          TopicDescription d =
              admin
                  .describeTopics(List.of(name))
                  .allTopicNames()
                  .get(5, TimeUnit.SECONDS)
                  .get(name);
          if (d.partitions().stream().allMatch(p -> p.leader() != null && !p.leader().isEmpty())) {
            return name;
          }
        } catch (ExecutionException notYetVisible) {
          // UnknownTopicOrPartition while the creation propagates: look again below.
        }
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("topic " + name + " has no leader after 30 s");
        }
        Thread.sleep(100);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not create topic " + name, e);
    }
  }

  /**
   * Runs relay cycles until no unpublished row remains (the observable end state) or {@link
   * #PUBLISH_DEADLINE}; returns the rows this relay marked published. A cycle whose first send
   * fails (fresh producer id, reconnect after a pause, a loaded machine) publishes 0 and is simply
   * followed by another one, as the scheduler would do.
   */
  private int publishAll(OutboxRelay r) throws InterruptedException {
    long deadline = System.nanoTime() + PUBLISH_DEADLINE.toNanos();
    int total = 0;
    while (pendingRows() > 0 && System.nanoTime() < deadline) {
      int n = r.publishPending();
      total += n;
      if (n == 0) {
        Thread.sleep(200);
      }
    }
    return total;
  }

  private int pendingRows() {
    return jdbc.queryForObject(
        "select count(*) from outbox_event where published_at is null", Integer.class);
  }

  private Instant publishedAt(UUID eventId) {
    return jdbc.queryForObject(
        "select published_at from outbox_event where event_id = ?", Instant.class, eventId);
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  /**
   * Reads the whole topic: every record up to the partition's end offset at call time. Callers
   * consume only after the relay marked the rows published (the broker acked them), so the end
   * offset already covers every send, duplicates included. The partition is assigned directly (no
   * consumer group, so no join or rebalance to wait for) and the loop ends on the position reaching
   * that end offset, with a deadline.
   */
  private static List<ConsumerRecord<String, String>> consume(String topic) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            REDPANDA.getBootstrapServers(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    TopicPartition partition = new TopicPartition(topic, 0);
    try (var consumer = new KafkaConsumer<String, String>(config)) {
      consumer.assign(List.of(partition));
      consumer.seekToBeginning(List.of(partition));
      long end = consumer.endOffsets(List.of(partition), Duration.ofSeconds(30)).get(partition);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (consumer.position(partition, Duration.ofSeconds(30)) < end) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException(
              "read " + records.size() + " of " + end + " records from " + topic + " in 30 s");
        }
        consumer.poll(Duration.ofMillis(250)).forEach(records::add);
      }
    }
    return records;
  }
}
