package dev.platform.reading.tuvi.adapter.in.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.platform.reading.tuvi.application.qa.QaRetentionUseCase;
import dev.platform.reading.tuvi.application.qa.QaRetryUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QaJobsTest {

  static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  final QaRetryUseCase retry = mock(QaRetryUseCase.class);
  final QaRetentionUseCase retention = mock(QaRetentionUseCase.class);
  final QaJobs jobs =
      new QaJobs(retry, retention, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1));

  @Test
  void runs_pass_the_clock_time() {
    jobs.runRetries();
    jobs.runRetention();

    verify(retry).runOnce(NOW);
    verify(retention).purge(NOW);
  }

  @Test
  void a_failed_run_never_throws() {
    doThrow(new IllegalStateException("db down")).when(retry).runOnce(any());
    doThrow(new IllegalStateException("db down")).when(retention).purge(any());

    assertThatCode(jobs::runRetries).doesNotThrowAnyException();
    assertThatCode(jobs::runRetention).doesNotThrowAnyException();
  }

  @Test
  void lifecycle_starts_and_stops_once() {
    assertThat(jobs.isRunning()).isFalse();
    jobs.start();
    jobs.start();
    assertThat(jobs.isRunning()).isTrue();
    jobs.stop();
    jobs.stop();
    assertThat(jobs.isRunning()).isFalse();
  }
}
