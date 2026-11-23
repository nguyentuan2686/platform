package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceIdsTest {

  static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

  @AfterEach
  void clearMdc() {
    MDC.remove("trace_id");
  }

  @Test
  void prefers_current_span_over_mdc() {
    MDC.put("trace_id", "from-mdc");
    try (Scope ignored = current(TRACE)) {
      assertThat(TraceIds.current()).isEqualTo(TRACE);
    }
  }

  @Test
  void falls_back_to_mdc_without_valid_span() {
    MDC.put("trace_id", "from-mdc");
    assertThat(TraceIds.current()).isEqualTo("from-mdc");
  }

  @Test
  void falls_back_to_mdc_when_opentelemetry_is_absent() {
    MDC.put("trace_id", "from-mdc");
    try (Scope ignored = current(TRACE)) {
      assertThat(TraceIds.current(false)).isEqualTo("from-mdc");
    }
  }

  @Test
  void empty_without_span_or_mdc() {
    assertThat(TraceIds.current()).isEmpty();
  }

  private static Scope current(String traceId) {
    SpanContext span =
        SpanContext.create(
            traceId, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
    return Context.root().with(Span.wrap(span)).makeCurrent();
  }
}
