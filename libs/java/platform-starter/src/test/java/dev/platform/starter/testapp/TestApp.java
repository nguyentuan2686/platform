package dev.platform.starter.testapp;

import dev.platform.starter.error.NotFoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tiny application used by the starter's web tests. The outbox ITs put JDBC on the test classpath;
 * these tests have no database, so DataSource auto-configuration is excluded here.
 */
@SpringBootApplication(
    excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class TestApp {

  /**
   * JDBC health support is on the test classpath (outbox ITs), so the platform defaults add 'db' to
   * the ready group; this app has no DataSource, so tests booting it use the plain ready group.
   */
  public static final String NO_DATABASE_READY_GROUP =
      "management.endpoint.health.group.ready.include=readinessState";

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

  /** Simulates a tracing filter: puts the X-Test-Trace header into the MDC as trace_id. */
  @Component
  static class TestTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String trace = request.getHeader("X-Test-Trace");
      if (trace != null) {
        MDC.put("trace_id", trace);
      }
      try {
        chain.doFilter(request, response);
      } finally {
        MDC.remove("trace_id");
      }
    }
  }
}
