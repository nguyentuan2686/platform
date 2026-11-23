package dev.platform.starter.outbox;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OutboxWriter} that inserts into {@code outbox_event} on the JDBC connection bound to the
 * caller's transaction, so the event commits or rolls back together with the business data.
 */
public class JdbcOutboxWriter implements OutboxWriter {

  static final String TRACEPARENT = "traceparent";

  private static final String INSERT =
      """
      insert into outbox_event
          (event_id, event_type, topic, msg_key, payload, traceparent, occurred_at)
      values (?, ?, ?, ?, ?::jsonb, ?, ?)
      """;

  private static final TextMapSetter<Map<String, String>> SETTER =
      (carrier, key, value) -> carrier.put(key, value);

  private final JdbcTemplate jdbc;
  private final JsonMapper jsonMapper;

  public JdbcOutboxWriter(DataSource dataSource, JsonMapper jsonMapper) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jsonMapper = jsonMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(OutboxEvent event) {
    jdbc.update(
        INSERT,
        event.eventId(),
        event.eventType(),
        event.topic(),
        event.key(),
        jsonMapper.writeValueAsString(event.data()),
        currentTraceparent(),
        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
  }

  /** W3C traceparent of the current span, or {@code null} when there is no valid span. */
  static String currentTraceparent() {
    Map<String, String> carrier = new HashMap<>(2);
    GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), carrier, SETTER);
    return carrier.get(TRACEPARENT);
  }
}
