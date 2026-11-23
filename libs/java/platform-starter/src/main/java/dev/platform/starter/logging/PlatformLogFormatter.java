package dev.platform.starter.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * One-JSON-object-per-line log format shared by all platform services (Java and Rust emit the same
 * mandatory fields): {@code timestamp, level, service, trace_id, span_id, message}.
 *
 * <p>Trace context is read from the MDC keys {@code trace_id} and {@code span_id}; missing values
 * are emitted as empty strings. Remaining MDC entries go under {@code ctx}; a logged throwable goes
 * under {@code error} ({@code type}, {@code stack}), never into {@code message}.
 */
public class PlatformLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

  static final String TRACE_ID = "trace_id";
  static final String SPAN_ID = "span_id";

  private final ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();
  private final JsonWriter<ILoggingEvent> writer;

  public PlatformLogFormatter(Environment environment) {
    String service = environment.getProperty("spring.application.name", "unknown");
    this.throwableConverter.start();
    this.writer =
        JsonWriter.<ILoggingEvent>of(
                members -> {
                  members.add(
                      "timestamp",
                      event -> DateTimeFormatter.ISO_INSTANT.format(event.getInstant()));
                  members.add("level", event -> event.getLevel().toString());
                  members.add("service", service);
                  members.add("trace_id", event -> mdc(event, TRACE_ID));
                  members.add("span_id", event -> mdc(event, SPAN_ID));
                  members.add("message", ILoggingEvent::getFormattedMessage);
                  members
                      .add("error", event -> event)
                      .whenNotNull(ILoggingEvent::getThrowableProxy)
                      .usingMembers(
                          error -> {
                            error.add("type", e -> e.getThrowableProxy().getClassName());
                            error.add("stack", this.throwableConverter::convert);
                          });
                  members.add("ctx", PlatformLogFormatter::context).whenNotEmpty();
                })
            .withNewLineAtEnd();
  }

  @Override
  public String format(ILoggingEvent event) {
    return this.writer.writeToString(event);
  }

  private static String mdc(ILoggingEvent event, String key) {
    String value = event.getMDCPropertyMap().get(key);
    return (value != null) ? value : "";
  }

  private static Map<String, String> context(ILoggingEvent event) {
    Map<String, String> ctx = new LinkedHashMap<>(event.getMDCPropertyMap());
    ctx.remove(TRACE_ID);
    ctx.remove(SPAN_ID);
    return ctx;
  }
}
