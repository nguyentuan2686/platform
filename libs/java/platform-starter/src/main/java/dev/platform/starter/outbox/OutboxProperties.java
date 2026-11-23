package dev.platform.starter.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code platform.outbox.*} settings.
 *
 * @param enabled switches the whole outbox (writer and relay) off when {@code false}
 * @param pollInterval delay between relay cycles
 * @param batchSize maximum rows published per cycle
 * @param sendTimeout how long one Kafka send may take before the cycle stops
 */
@ConfigurationProperties("platform.outbox")
public record OutboxProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("500ms") Duration pollInterval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("5s") Duration sendTimeout) {}
