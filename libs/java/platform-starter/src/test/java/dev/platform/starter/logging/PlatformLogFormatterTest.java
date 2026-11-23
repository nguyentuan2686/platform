package dev.platform.starter.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlatformLogFormatterTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  private final PlatformLogFormatter formatter =
      new PlatformLogFormatter(
          new MockEnvironment().withProperty("spring.application.name", "order-svc"));

  private static LoggingEvent event(Map<String, String> mdc, Throwable error) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(new LoggerContext());
    event.setLoggerName("test");
    event.setLevel(Level.INFO);
    event.setMessage("hello");
    event.setInstant(Instant.parse("2026-01-02T03:04:05.123Z"));
    event.setMDCPropertyMap(mdc);
    if (error != null) {
      event.setThrowableProxy(new ThrowableProxy(error));
    }
    return event;
  }

  private static Map<String, Object> parse(String json) {
    return JsonPath.parse(json).read("$");
  }

  @Test
  void formats_mandatory_fields() {
    String out = formatter.format(event(Map.of("trace_id", TRACE_ID, "span_id", SPAN_ID), null));

    Map<String, Object> json = parse(out);
    assertThat(json)
        .containsKeys("timestamp", "level", "service", "trace_id", "span_id", "message");
    assertThat(json).containsEntry("level", "INFO");
    assertThat(json).containsEntry("service", "order-svc");
    assertThat(json).containsEntry("message", "hello");
    assertThat(json).containsEntry("trace_id", TRACE_ID);
    assertThat(json).containsEntry("span_id", SPAN_ID);
    assertThat(Instant.parse((String) json.get("timestamp")))
        .isEqualTo(Instant.parse("2026-01-02T03:04:05.123Z"));
    assertThat((String) json.get("timestamp")).endsWith("Z");
  }

  @Test
  void missing_trace_context_emits_empty_strings() {
    Map<String, Object> json = parse(formatter.format(event(Map.of(), null)));

    assertThat(json).containsEntry("trace_id", "").containsEntry("span_id", "");
  }

  @Test
  void exception_goes_to_error_field_not_message() {
    Map<String, Object> json =
        parse(formatter.format(event(Map.of(), new IllegalStateException("boom"))));

    assertThat(json).containsEntry("message", "hello");
    assertThat(JsonPath.<String>read(json, "$.error.type"))
        .isEqualTo("java.lang.IllegalStateException");
    assertThat(JsonPath.<String>read(json, "$.error.stack")).isNotEmpty();
  }

  @Test
  void other_mdc_entries_are_nested_under_ctx() {
    Map<String, Object> json =
        parse(formatter.format(event(Map.of("order_id", "42", "trace_id", TRACE_ID), null)));

    assertThat(JsonPath.<String>read(json, "$.ctx.order_id")).isEqualTo("42");
    assertThat(JsonPath.<Object>read(json, "$.ctx")).isInstanceOf(Map.class);
    assertThat(JsonPath.<Map<String, Object>>read(json, "$.ctx"))
        .doesNotContainKey("trace_id")
        .doesNotContainKey("span_id");
  }

  @Test
  void output_is_single_line_ending_with_newline() {
    String out = formatter.format(event(Map.of(), new IllegalStateException("boom")));

    assertThat(out).endsWith("\n");
    assertThat(out.substring(0, out.length() - 1)).doesNotContain("\n");
  }
}
