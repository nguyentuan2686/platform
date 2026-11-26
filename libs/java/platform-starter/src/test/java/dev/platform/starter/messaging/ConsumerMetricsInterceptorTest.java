package dev.platform.starter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.platform.starter.telemetry.PlatformMeter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumerMetricsInterceptorTest {

  InMemoryMetricReader reader;
  ConsumerMetricsInterceptor interceptor;
  ConsumerRecord<Object, Object> record = new ConsumerRecord<>("orders.v1", 0, 0L, "k", "v");

  @SuppressWarnings("unchecked")
  Consumer<Object, Object> consumer = mock(Consumer.class);

  @BeforeEach
  void setUp() {
    reader = InMemoryMetricReader.create();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    interceptor = new ConsumerMetricsInterceptor(provider.get("test"));
    when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("grp"));
  }

  List<HistogramPointData> points() {
    return reader.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("messaging.process.duration"))
        .flatMap(m -> m.getHistogramData().getPoints().stream())
        .toList();
  }

  @Test
  void success_records_processed() {
    interceptor.intercept(record, consumer);
    interceptor.success(record, consumer);
    interceptor.afterRecord(record, consumer);

    assertThat(points()).hasSize(1);
    HistogramPointData p = points().get(0);
    assertThat(p.getCount()).isEqualTo(1);
    assertThat(p.getAttributes().get(ConsumerMetricsInterceptor.TOPIC)).isEqualTo("orders.v1");
    assertThat(p.getAttributes().get(ConsumerMetricsInterceptor.GROUP)).isEqualTo("grp");
    assertThat(p.getAttributes().get(ConsumerMetricsInterceptor.OUTCOME)).isEqualTo("processed");
    assertThat(p.getAttributes().size()).isEqualTo(3);
  }

  @Test
  void failure_records_failed() {
    interceptor.intercept(record, consumer);
    interceptor.failure(record, new IllegalStateException("x"), consumer);
    interceptor.afterRecord(record, consumer);

    assertThat(points()).hasSize(1);
    assertThat(points().get(0).getAttributes().get(ConsumerMetricsInterceptor.OUTCOME))
        .isEqualTo("failed");
  }

  @Test
  void thread_local_cleared_after_record() {
    interceptor.intercept(record, consumer);
    interceptor.success(record, consumer);
    interceptor.afterRecord(record, consumer);
    interceptor.success(record, consumer); // no intercept: nothing to measure

    assertThat(points().get(0).getCount()).isEqualTo(1);
  }

  @Test
  void uses_the_shared_bucket_boundaries() {
    interceptor.intercept(record, consumer);
    interceptor.success(record, consumer);

    assertThat(points().get(0).getBoundaries())
        .containsExactlyElementsOf(PlatformMeter.DURATION_BUCKETS_S);
  }
}
