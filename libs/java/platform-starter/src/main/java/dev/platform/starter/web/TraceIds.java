package dev.platform.starter.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Resolves the current trace id for problem responses.
 *
 * <p>The OpenTelemetry Java agent makes the server span current but does <em>not</em> call {@code
 * MDC.put}: its Logback instrumentation only adds {@code trace_id}/{@code span_id} to each logging
 * event's MDC copy. So the id is taken from {@code Span.current()} when a valid span is current,
 * then from the MDC (other tracing setups), else {@code ""}.
 *
 * <p>{@code opentelemetry-api} is optional in the starter; all references to it live in the nested
 * {@link Otel} class, which is only loaded when the API is on the classpath.
 */
final class TraceIds {

  static final String MDC_KEY = "trace_id";

  private static final boolean OTEL_PRESENT =
      ClassUtils.isPresent("io.opentelemetry.api.trace.Span", TraceIds.class.getClassLoader());

  private TraceIds() {}

  /** The current trace id, or {@code ""} when there is none. */
  static String current() {
    return current(OTEL_PRESENT);
  }

  static String current(boolean otelPresent) {
    if (otelPresent) {
      String id = Otel.currentTraceId();
      if (id != null) {
        return id;
      }
    }
    String mdc = MDC.get(MDC_KEY);
    return mdc == null ? "" : mdc;
  }

  private static final class Otel {

    @Nullable
    static String currentTraceId() {
      SpanContext context = Span.current().getSpanContext();
      return context.isValid() ? context.getTraceId() : null;
    }
  }
}
