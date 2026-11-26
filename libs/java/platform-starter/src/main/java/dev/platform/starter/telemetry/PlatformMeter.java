package dev.platform.starter.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The meter all platform metrics are created from. In production the OpenTelemetry Java agent
 * provides the global instance; without an agent the global instance is a no-op, so recording costs
 * nothing and never fails.
 */
public final class PlatformMeter {

  /** Instrumentation scope of the platform metrics. */
  public static final String SCOPE = "dev.platform.starter";

  /** Histogram buckets in seconds; the same list as the Rust platform-core. */
  public static final List<Double> DURATION_BUCKETS_S =
      List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);

  private PlatformMeter() {}

  /** An {@link OpenTelemetry} bean when the application defines one (tests), else the global. */
  public static Meter of(ObjectProvider<OpenTelemetry> otel) {
    return otel.getIfAvailable(GlobalOpenTelemetry::get).getMeter(SCOPE);
  }
}
