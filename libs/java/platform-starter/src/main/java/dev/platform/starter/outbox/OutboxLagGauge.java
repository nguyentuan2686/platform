package dev.platform.starter.outbox;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gauge {@code platform.outbox.lag} (seconds): the age of the oldest unpublished outbox row, 0 when
 * none. Evaluated on each metric collection; a failing query logs a warning and skips the
 * observation.
 */
public class OutboxLagGauge implements AutoCloseable {

  static final String SQL =
      "select coalesce(extract(epoch from now() - min(occurred_at)), 0) "
          + "from outbox_event where published_at is null";

  private static final Logger log = LoggerFactory.getLogger(OutboxLagGauge.class);

  private final JdbcTemplate jdbc;
  private final ObservableDoubleGauge gauge;

  public OutboxLagGauge(DataSource dataSource, Meter meter) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(5);
    this.gauge =
        meter
            .gaugeBuilder("platform.outbox.lag")
            .setUnit("s")
            .setDescription("Age of the oldest unpublished outbox row, 0 when none")
            .buildWithCallback(
                measurement -> {
                  try {
                    Double lag = jdbc.queryForObject(SQL, Double.class);
                    if (lag != null) {
                      measurement.record(lag);
                    }
                  } catch (RuntimeException e) {
                    log.warn(
                        "Outbox lag query failed, observation skipped cause={}",
                        e.getClass().getSimpleName());
                  }
                });
  }

  @Override
  public void close() {
    gauge.close();
  }
}
