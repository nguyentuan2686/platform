package dev.platform.payment.adapter.in.schedule;

import dev.platform.payment.application.ReconcileSepayUseCase;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs reconciliation every {@code interval} on its own single thread (no application-wide
 * scheduling). A failed run logs one WARN and the next run retries; the thread never dies.
 */
public class SepayReconcileJob implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(SepayReconcileJob.class);

  private final ReconcileSepayUseCase reconcile;
  private final Duration interval;
  private ScheduledExecutorService executor;

  public SepayReconcileJob(ReconcileSepayUseCase reconcile, Duration interval) {
    this.reconcile = reconcile;
    this.interval = interval;
  }

  /** One run; never throws. */
  public void runOnce() {
    try {
      ReconcileSepayUseCase.Summary summary = reconcile.run();
      LOG.info(
          "sepay reconcile done: total={} matched={} unmatched={} duplicates={} failed={}",
          summary.total(),
          summary.matched(),
          summary.unmatched(),
          summary.duplicates(),
          summary.failed());
    } catch (RuntimeException e) {
      LOG.warn("sepay reconcile failed: {}", e.getClass().getSimpleName());
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
              Thread thread = new Thread(runnable, "sepay-reconcile");
              thread.setDaemon(true);
              return thread;
            });
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(this::runOnce, millis, millis, TimeUnit.MILLISECONDS);
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
