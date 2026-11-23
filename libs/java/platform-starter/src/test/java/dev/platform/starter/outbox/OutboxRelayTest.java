package dev.platform.starter.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** Relay cycle behaviour that needs no database or broker. */
class OutboxRelayTest {

  @Test
  void cycle_runs_under_a_valid_non_sampled_parent_so_its_jdbc_spans_are_not_traced() {
    List<SpanContext> seen = new ArrayList<>();
    Supplier<RuntimeException> failure = () -> new IllegalStateException("stop after capture");
    try (OutboxRelay relay = relay(stub(seen, failure))) {
      relay.poll();
      relay.poll();
    }

    assertThat(seen).hasSize(2);
    for (SpanContext context : seen) {
      assertThat(context.isValid()).isTrue();
      assertThat(context.isSampled()).isFalse();
    }
    // A fresh trace id per cycle keeps the relay's log lines of different cycles apart.
    assertThat(seen.get(0).getTraceId()).isNotEqualTo(seen.get(1).getTraceId());
  }

  @Test
  void cycle_ignores_a_sampled_context_current_on_the_calling_thread() {
    List<SpanContext> seen = new ArrayList<>();
    SpanContext request =
        SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    try (OutboxRelay relay = relay(stub(seen, () -> new IllegalStateException("stop")));
        Scope ignored = Context.root().with(Span.wrap(request)).makeCurrent()) {
      relay.poll();
    }

    assertThat(seen.get(0).isSampled()).isFalse();
    assertThat(seen.get(0).getTraceId()).isNotEqualTo(request.getTraceId());
  }

  @Test
  void poll_survives_an_error_so_the_fixed_delay_schedule_is_not_cancelled() {
    try (OutboxRelay relay = relay(stub(new ArrayList<>(), null))) {
      assertThatNoException().isThrownBy(relay::poll);
      assertThatNoException().isThrownBy(relay::poll);
    }
  }

  /**
   * A transaction manager that records the span context current when the cycle opens its
   * transaction, then fails: with {@code failure} when given, else with an {@link Error}.
   */
  private static PlatformTransactionManager stub(
      List<SpanContext> seen, Supplier<RuntimeException> failure) {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        seen.add(Span.current().getSpanContext());
        if (failure != null) {
          throw failure.get();
        }
        throw new NoClassDefFoundError("simulated linkage error");
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static OutboxRelay relay(PlatformTransactionManager transactionManager) {
    var kafka =
        new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<String, String>(
                Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    return new OutboxRelay(
        new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none"),
        transactionManager,
        kafka,
        JsonMapper.shared(),
        new OutboxProperties(true, Duration.ofHours(1), 10, Duration.ofSeconds(1)));
  }
}
