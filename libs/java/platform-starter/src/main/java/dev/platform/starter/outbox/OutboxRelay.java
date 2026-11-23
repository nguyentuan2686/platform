package dev.platform.starter.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes pending outbox rows to Kafka (at-least-once, ordered by {@code occurred_at}).
 *
 * <p>Each cycle runs in one database transaction: it locks up to {@code batch-size} unpublished
 * rows with {@code FOR UPDATE SKIP LOCKED} (so concurrent relay instances never pick the same row),
 * sends them one by one, waiting for the broker ack, and marks each sent row published. A failed
 * send increments that row's {@code attempts}, stops the cycle (preserving order) and commits what
 * was published so far. A crash between send and commit re-sends the row later with the same {@code
 * event_id}; consumers deduplicate on it.
 *
 * <p>The record value is the envelope {@code {event_id, event_type, occurred_at, data}}; headers
 * are {@code event_id}, {@code event_type} and, when the row has one, the W3C {@code traceparent}
 * captured at append time. The send runs with that trace context current, so an instrumented
 * producer (e.g. the OpenTelemetry agent) records the produce span in the original request trace.
 */
public class OutboxRelay implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private static final String SELECT_PENDING =
      """
      select event_id, event_type, topic, msg_key, payload::text as payload, traceparent,
             occurred_at
      from outbox_event
      where published_at is null
      order by occurred_at
      limit ?
      for update skip locked
      """;
  private static final String MARK_PUBLISHED =
      "update outbox_event set published_at = now() where event_id = ?";
  private static final String INCREMENT_ATTEMPTS =
      "update outbox_event set attempts = attempts + 1 where event_id = ?";

  private static final TextMapGetter<Row> TRACEPARENT_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Row row) {
          return List.of(JdbcOutboxWriter.TRACEPARENT);
        }

        @Override
        public String get(Row row, String key) {
          return (row != null && JdbcOutboxWriter.TRACEPARENT.equals(key))
              ? row.traceparent()
              : null;
        }
      };

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final DefaultKafkaProducerFactory<String, String> producerFactory;
  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper jsonMapper;
  private final OutboxProperties properties;

  /** Test hook: runs after a successful send and before the row is marked published. */
  volatile Runnable beforeMark = () -> {};

  /**
   * @param kafkaTemplate the application's template; its producer configuration (bootstrap servers,
   *     security, timeouts) is reused for a dedicated outbox producer with String serializers,
   *     {@code acks=all} and idempotence enabled
   */
  public OutboxRelay(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      KafkaTemplate<?, ?> kafkaTemplate,
      JsonMapper jsonMapper,
      OutboxProperties properties) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.transaction = new TransactionTemplate(transactionManager);
    Map<String, Object> config =
        new HashMap<>(kafkaTemplate.getProducerFactory().getConfigurationProperties());
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    // send() blocks for metadata up to max.block.ms (default 60s) while this cycle holds row
    // locks; unless configured explicitly, bound it by the send timeout.
    config.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.sendTimeout().toMillis());
    this.producerFactory =
        new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    this.kafka = new KafkaTemplate<>(producerFactory);
    this.jsonMapper = jsonMapper;
    this.properties = properties;
  }

  /**
   * Publishes one batch of pending rows.
   *
   * @return number of rows published (and marked) in this cycle; 0 when nothing was pending or the
   *     first send failed
   */
  public int publishPending() {
    Integer published = transaction.execute(status -> publishBatch());
    return (published != null) ? published : 0;
  }

  /** Scheduled entry point: drains full batches; never lets an exception end the schedule. */
  void poll() {
    try {
      int published;
      do {
        published = publishPending();
      } while (published == properties.batchSize());
    } catch (RuntimeException e) {
      log.error("Outbox relay cycle failed; rows stay pending and will be retried", e);
    }
  }

  private int publishBatch() {
    List<Row> rows = jdbc.query(SELECT_PENDING, OutboxRelay::row, properties.batchSize());
    int published = 0;
    for (Row row : rows) {
      if (!send(row)) {
        jdbc.update(INCREMENT_ATTEMPTS, row.eventId());
        break;
      }
      beforeMark.run();
      jdbc.update(MARK_PUBLISHED, row.eventId());
      published++;
    }
    return published;
  }

  /** Sends one row and waits for the broker ack; {@code false} on any send failure. */
  private boolean send(Row row) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(row.topic(), null, row.key(), envelope(row));
    header(record, "event_id", row.eventId().toString());
    header(record, "event_type", row.eventType());
    if (row.traceparent() != null) {
      header(record, JdbcOutboxWriter.TRACEPARENT, row.traceparent());
    }
    Context trace =
        GlobalOpenTelemetry.getPropagators()
            .getTextMapPropagator()
            .extract(Context.root(), row, TRACEPARENT_GETTER);
    try (Scope ignored = trace.makeCurrent()) {
      kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      warn(row, e);
      return false;
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      warn(row, e);
      return false;
    }
  }

  private String envelope(Row row) {
    ObjectNode envelope = jsonMapper.createObjectNode();
    envelope.put("event_id", row.eventId().toString());
    envelope.put("event_type", row.eventType());
    envelope.put("occurred_at", row.occurredAt().toString());
    envelope.set("data", jsonMapper.readTree(row.payload()));
    return jsonMapper.writeValueAsString(envelope);
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void warn(Row row, Exception e) {
    Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
    log.warn(
        "Outbox publish failed for event_id={} event_type={}; will retry: {}",
        row.eventId(),
        row.eventType(),
        cause.toString());
  }

  private static Row row(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        rs.getString("topic"),
        rs.getString("msg_key"),
        rs.getString("payload"),
        rs.getString("traceparent"),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }

  /** Closes the dedicated outbox producer. */
  @Override
  public void close() {
    producerFactory.destroy();
  }

  private record Row(
      UUID eventId,
      String eventType,
      String topic,
      String key,
      String payload,
      String traceparent,
      Instant occurredAt) {}
}
