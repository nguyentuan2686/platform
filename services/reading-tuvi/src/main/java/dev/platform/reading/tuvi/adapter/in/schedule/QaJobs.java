package dev.platform.reading.tuvi.adapter.in.schedule;

import dev.platform.reading.tuvi.application.qa.QaRetentionUseCase;
import dev.platform.reading.tuvi.application.qa.QaRetryUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Q&A background jobs on one thread of their own (no application-wide scheduling): the retry /
 * refund / recovery run every {@code QA_JOB_INTERVAL} and retention every 6 hours. Started only
 * when {@code QA_JOBS_ENABLED=true}. A failed run logs one WARN and the next run retries; the
 * thread never dies. Several replicas may run it: the use cases claim rows.
 */
public class QaJobs implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(QaJobs.class);

  static final Duration RETENTION_INTERVAL = Duration.ofHours(6);
  static final Duration RETENTION_FIRST_DELAY = Duration.ofMinutes(5);

  private final QaRetryUseCase retry;
  private final QaRetentionUseCase retention;
  private final Clock clock;
  private final Duration interval;
  private ScheduledExecutorService executor;

  public QaJobs(
      QaRetryUseCase retry, QaRetentionUseCase retention, Clock clock, Duration interval) {
    this.retry = retry;
    this.retention = retention;
    this.clock = clock;
    this.interval = interval;
  }

  /** One retry run; never throws (except a VM error), so the schedule never stops. */
  public void runRetries() {
    try {
      retry.runOnce();
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      LOG.warn("qa retry run failed: {}", e.getClass().getSimpleName());
    }
  }

  /** One retention run; never throws (except a VM error). */
  public void runRetention() {
    try {
      retention.purge(clock.instant());
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      LOG.warn("qa retention run failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "qa-jobs");
              thread.setDaemon(true);
              return thread;
            });
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(this::runRetries, millis, millis, TimeUnit.MILLISECONDS);
    executor.scheduleWithFixedDelay(
        this::runRetention,
        RETENTION_FIRST_DELAY.toMillis(),
        RETENTION_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    executor = null;
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
