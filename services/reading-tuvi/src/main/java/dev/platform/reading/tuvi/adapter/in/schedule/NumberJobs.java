package dev.platform.reading.tuvi.adapter.in.schedule;

import dev.platform.reading.tuvi.application.number.NumberCheckService;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Deletes number-check usage older than yesterday, once a day on its own thread (first run ten
 * minutes after start). Started only when {@code NUMBER_CHECK_JOBS_ENABLED=true}. A failed run logs
 * one WARN; the next run retries. Several replicas may run it: the delete is idempotent.
 */
public class NumberJobs implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(NumberJobs.class);

  static final Duration INTERVAL = Duration.ofDays(1);
  static final Duration FIRST_DELAY = Duration.ofMinutes(10);

  private final NumberCheckService service;
  private ScheduledExecutorService executor;

  public NumberJobs(NumberCheckService service) {
    this.service = service;
  }

  /** One purge; never throws (except a VM error). */
  public void runPurge() {
    try {
      int n = service.purgeExpired();
      LOG.info("number-check usage purge deleted {} rows", n);
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      LOG.warn("number-check usage purge failed: {}", e.getClass().getSimpleName());
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
              Thread thread = new Thread(runnable, "number-jobs");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(
        this::runPurge, FIRST_DELAY.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    executor = null;
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
