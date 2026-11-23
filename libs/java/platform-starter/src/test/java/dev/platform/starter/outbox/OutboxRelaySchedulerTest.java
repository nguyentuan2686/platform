package dev.platform.starter.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxRelaySchedulerTest {

  @Test
  void runs_cycle_repeatedly_on_its_own_thread() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch threeRuns = new CountDownLatch(3);
    String[] threadName = new String[1];
    var scheduler =
        new OutboxRelayScheduler(
            () -> {
              threadName[0] = Thread.currentThread().getName();
              runs.incrementAndGet();
              threeRuns.countDown();
            },
            Duration.ofMillis(10),
            Duration.ofSeconds(5));

    scheduler.start();
    try {
      assertThat(threeRuns.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      scheduler.stop();
    }
    assertThat(threadName[0]).isEqualTo("outbox-relay");
    int afterStop = runs.get();
    Thread.sleep(100);
    assertThat(runs.get()).isEqualTo(afterStop);
    assertThat(scheduler.isRunning()).isFalse();
  }

  @Test
  void stop_waits_for_in_flight_cycle() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean finished = new AtomicBoolean();
    var scheduler =
        new OutboxRelayScheduler(
            () -> {
              started.countDown();
              try {
                Thread.sleep(300);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              finished.set(true);
            },
            Duration.ofMillis(1),
            Duration.ofSeconds(5));

    scheduler.start();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    scheduler.stop();

    assertThat(finished).isTrue();
  }
}
