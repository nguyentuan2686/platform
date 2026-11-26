package dev.platform.starter.messaging;

import dev.platform.starter.telemetry.PlatformMeter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Records {@code messaging.process.duration} for every record a listener handles, labelled with
 * topic, consumer group and {@code outcome} ({@code processed} or {@code failed}). A failed record
 * that the error handler retries is counted once per attempt.
 */
public class ConsumerMetricsInterceptor implements RecordInterceptor<Object, Object> {

  static final AttributeKey<String> TOPIC = AttributeKey.stringKey("messaging.destination.name");
  static final AttributeKey<String> GROUP = AttributeKey.stringKey("messaging.consumer.group.name");
  static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

  private final DoubleHistogram duration;
  private final ThreadLocal<Long> startedAt = new ThreadLocal<>();

  public ConsumerMetricsInterceptor(Meter meter) {
    this.duration =
        meter
            .histogramBuilder("messaging.process.duration")
            .setUnit("s")
            .setDescription("Duration of processing one consumed message")
            .setExplicitBucketBoundariesAdvice(PlatformMeter.DURATION_BUCKETS_S)
            .build();
  }

  @Override
  public ConsumerRecord<Object, Object> intercept(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    startedAt.set(System.nanoTime());
    return record;
  }

  @Override
  public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    record(record, consumer, "processed");
  }

  @Override
  public void failure(
      ConsumerRecord<Object, Object> record,
      Exception exception,
      Consumer<Object, Object> consumer) {
    record(record, consumer, "failed");
  }

  @Override
  public void afterRecord(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    startedAt.remove();
  }

  private void record(
      ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer, String outcome) {
    Long start = startedAt.get();
    if (start == null) {
      return;
    }
    startedAt.remove();
    duration.record(
        (System.nanoTime() - start) / 1_000_000_000.0,
        Attributes.of(
            TOPIC, record.topic(),
            GROUP, consumer.groupMetadata().groupId(),
            OUTCOME, outcome));
  }
}
