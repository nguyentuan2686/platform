package dev.platform.starter.outbox;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the relay cycle on a private single thread with a fixed delay. Owning the thread keeps the
 * starter from enabling {@code @Scheduled} processing application-wide or sharing the application's
 * task scheduler.
 *
 * <p>Started with the context; {@link #stop()} waits (bounded) for an in-flight cycle, so it ends
 * before the relay's producer and the DataSource are closed during singleton destruction.
 */
public class OutboxRelayScheduler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

  private final Runnable cycle;
  private final Duration interval;
  private final Duration stopTimeout;
  private ScheduledExecutorService executor;

  /**
   * @param cycle one relay cycle; must not throw (see {@link OutboxRelay#poll()})
   * @param interval delay between the end of one cycle and the start of the next (also the initial
   *     delay)
   * @param stopTimeout how long {@link #stop()} waits for an in-flight cycle
   */
  public OutboxRelayScheduler(Runnable cycle, Duration interval, Duration stopTimeout) {
    this.cycle = cycle;
    this.interval = interval;
    this.stopTimeout = stopTimeout;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "outbox-relay");
              thread.setDaemon(true);
              return thread;
            });
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(cycle, millis, millis, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        log.warn("Outbox relay cycle did not finish within {}; interrupting it", stopTimeout);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      executor = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
