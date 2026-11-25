package dev.platform.payment.adapter.in.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.platform.payment.application.ReconcileSepayUseCase;
import dev.platform.payment.application.RecordBankTransactionUseCase;
import dev.platform.payment.application.UpstreamUnavailableException;
import dev.platform.payment.support.InMemoryOrders;
import dev.platform.payment.support.InMemoryProviderTransactions;
import dev.platform.payment.support.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
class SepayReconcileJobTest {

  final List<String> calls = new ArrayList<>();
  boolean fail = true;

  final ReconcileSepayUseCase useCase =
      new ReconcileSepayUseCase(
          lookback -> {
            calls.add("run");
            if (fail) {
              throw new UpstreamUnavailableException("sepay");
            }
            return List.of();
          },
          new RecordBankTransactionUseCase(
              new InMemoryOrders(),
              new InMemoryProviderTransactions(),
              new RecordingEvents(),
              TransactionOperations.withoutTransaction()),
          Duration.ofHours(24));

  @Test
  void an_upstream_failure_is_logged_as_a_warning_and_the_next_run_retries(CapturedOutput output) {
    SepayReconcileJob job = new SepayReconcileJob(useCase, Duration.ofMinutes(5));

    assertThatCode(job::runOnce).doesNotThrowAnyException();
    assertThat(output.getAll()).contains("sepay reconcile failed");

    fail = false;
    assertThatCode(job::runOnce).doesNotThrowAnyException();
    assertThat(calls).hasSize(2);
  }

  @Test
  void lifecycle_starts_and_stops_cleanly() {
    SepayReconcileJob job = new SepayReconcileJob(useCase, Duration.ofHours(1));

    job.start();
    assertThat(job.isRunning()).isTrue();
    job.stop();
    assertThat(job.isRunning()).isFalse();
  }
}
