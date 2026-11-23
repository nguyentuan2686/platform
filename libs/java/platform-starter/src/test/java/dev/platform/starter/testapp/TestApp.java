package dev.platform.starter.testapp;

import dev.platform.starter.error.NotFoundException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tiny application used by the starter's web tests: a web app without a database. JDBC is on the
 * test classpath (outbox ITs) but no {@code spring.datasource.url} is set and no embedded database
 * exists, so DataSource auto-configuration would fail startup; it is excluded here.
 */
@SpringBootApplication(
    excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class TestApp {

  public record Item(@Min(1) int quantity) {}

  public record CreateRequest(@NotBlank String name, @Valid List<Item> items) {}

  @RestController
  static class BoomController {

    @GetMapping("/boom/not-found")
    String notFound() {
      throw new NotFoundException("order", "x");
    }

    @PostMapping("/boom/validate")
    String validate(@Valid @RequestBody CreateRequest request) {
      return "ok";
    }

    @GetMapping("/boom/unexpected")
    String unexpected() {
      throw new RuntimeException("db password=secret");
    }
  }

  /**
   * Simulates the OpenTelemetry agent's server instrumentation: makes a span with the {@code
   * X-Test-Trace} trace id current for the request. Like the agent, it never touches the MDC.
   */
  @Component
  static class TestTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String trace = request.getHeader("X-Test-Trace");
      if (trace == null) {
        chain.doFilter(request, response);
        return;
      }
      SpanContext span =
          SpanContext.create(
              trace, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
      try (Scope ignored = Context.root().with(Span.wrap(span)).makeCurrent()) {
        chain.doFilter(request, response);
      }
    }
  }
}
