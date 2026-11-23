package dev.platform.starter.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * An integration event to publish through the transactional outbox.
 *
 * @param eventId unique id; consumers deduplicate on it (delivery is at-least-once)
 * @param eventType envelope {@code event_type}, e.g. {@code OrderPlaced}
 * @param topic Kafka topic
 * @param key Kafka record key (ordering is per key)
 * @param occurredAt envelope {@code occurred_at}; stored with microsecond precision
 * @param data envelope {@code data}
 */
public record OutboxEvent(
    UUID eventId, String eventType, String topic, String key, Instant occurredAt, JsonNode data) {

  public OutboxEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(data, "data");
  }
}
