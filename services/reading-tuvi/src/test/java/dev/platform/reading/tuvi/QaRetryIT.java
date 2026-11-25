package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.platform.reading.tuvi.adapter.in.schedule.QaJobs;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.llm.AllProvidersExhaustedException;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort;
import dev.platform.reading.tuvi.application.qa.QaRetentionUseCase;
import dev.platform.reading.tuvi.application.qa.QaRetryUseCase;
import dev.platform.reading.tuvi.application.qa.QaService;
import dev.platform.reading.tuvi.application.qa.QaSettings;
import dev.platform.reading.tuvi.application.qa.RefundOutcome;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * The retry job against Postgres: a paid question fails while the model is down, then two job
 * instances (two replicas) run at once on the same due row and regenerate it exactly once.
 */
class QaRetryIT extends QaItSupport {

  static final String QUESTION = "Năm nay chuyện tình duyên của tôi thế nào";

  @MockitoSpyBean LlmGateway gateway;
  @Autowired QaRetryUseCase retry;
  @Autowired QaRetentionUseCase retention;
  @Autowired Clock clock;

  QaJobs jobs() {
    return new QaJobs(retry, retention, clock, Duration.ofMinutes(1));
  }

  String status(UUID id) {
    return jdbc.queryForObject("select status from qa_reading where id = ?", String.class, id);
  }

  /** Model down (like {@code LLM_FAKE_MODE=fail}): the charged question is RETRYING. */
  UUID retrying() throws Exception {
    doThrow(new AllProvidersExhaustedException()).when(gateway).complete(any());
    UUID id = UUID.randomUUID();
    HttpResponse<String> res =
        send("POST", "/tuvi/qa/readings", ask(id, "PAID", "TinhDuyen", QUESTION), ACCOUNT_BEARER);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(status(id)).isEqualTo("RETRYING");
    return id;
  }

  @Test
  void two_jobs_on_the_same_due_row_regenerate_once() throws Exception {
    UUID id = retrying();
    jdbc.update(
        "update qa_reading set next_attempt_at = now() - interval '1 minute' where id = ?", id);
    // the model is back (provider switched); slow, so both job runs overlap
    clearInvocations(gateway);
    doAnswer(
            inv -> {
              Thread.sleep(500);
              return inv.callRealMethod();
            })
        .when(gateway)
        .complete(any());

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (QaJobs job : List.of(jobs(), jobs())) {
        runs.add(
            pool.submit(
                () -> {
                  start.await();
                  job.runRetries();
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> f : runs) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    verify(gateway, times(1)).complete(any());
    assertThat(status(id)).isEqualTo("ANSWERED");
    JsonNode reading = getJson("/tuvi/qa/readings/" + id, ACCOUNT_BEARER);
    assertThat(reading.get("messages").get(0).get("answer").asString()).startsWith("Con hỏi về");
    assertThat(
            jdbc.queryForObject(
                "select prompt_enc is null from qa_message where reading_id = ?",
                Boolean.class,
                id))
        .isTrue();
    verify(identity, never()).refund(any());
  }

  @Test
  void elapsed_window_refunds_and_the_reading_says_so() throws Exception {
    UUID id = retrying();
    jdbc.update(
        "update qa_reading set next_attempt_at = now() - interval '1 minute',"
            + " retry_until = now() - interval '1 second' where id = ?",
        id);

    jobs().runRetries();
    jobs().runRetries();

    verify(identity, times(1)).refund(eq(id));
    assertThat(status(id)).isEqualTo("REFUNDED");
    JsonNode reading = getJson("/tuvi/qa/readings/" + id, ACCOUNT_BEARER);
    assertThat(reading.get("status").asString()).isEqualTo("REFUNDED");
    assertThat(reading.get("notice").asString()).isNotBlank();
  }

  @Autowired QaRepositoryPort repo;
  @Autowired QaService qa;
  @Autowired QaSettings settings;

  QaRetryUseCase useCase(Duration ahead) {
    return new QaRetryUseCase(repo, qa, identity, settings, Clock.offset(Clock.systemUTC(), ahead));
  }

  @Test
  void a_run_stalled_past_its_lease_cannot_overwrite_the_next_claimer() throws Exception {
    UUID id = retrying();
    jdbc.update(
        "update qa_reading set next_attempt_at = now() - interval '1 minute' where id = ?", id);
    clearInvocations(gateway);
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    // run A's model call stalls; run B's (after A's lease) fails
    doAnswer(
            inv -> {
              if (calls.incrementAndGet() == 1) {
                inCall.countDown();
                release.await(30, TimeUnit.SECONDS);
                return inv.callRealMethod();
              }
              throw new AllProvidersExhaustedException();
            })
        .when(gateway)
        .complete(any());

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> runA = pool.submit(() -> useCase(Duration.ZERO).runOnce());
      assertThat(inCall.await(30, TimeUnit.SECONDS)).isTrue();
      useCase(QaRetryUseCaseLeases.RETRY.plusMinutes(1)).runOnce();
      release.countDown();
      runA.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    verify(gateway, times(2)).complete(any());
    // only B's write landed: A's late answer was fenced out
    assertThat(status(id)).isEqualTo("RETRYING");
    assertThat(
            jdbc.queryForObject("select attempts from qa_reading where id = ?", Integer.class, id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select answer_enc is null from qa_message where reading_id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void a_refund_stalled_past_its_lease_cannot_overwrite_the_next_claimer() throws Exception {
    UUID id = retrying();
    jdbc.update(
        "update qa_reading set status = 'REFUND_PENDING', next_attempt_at = null where id = ?", id);
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            inv -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                inCall.countDown();
                release.await(30, TimeUnit.SECONDS);
                return RefundOutcome.REFUNDED;
              }
              if (n == 2) {
                throw new UpstreamUnavailableException("identity");
              }
              return RefundOutcome.REFUNDED;
            })
        .when(identity)
        .refund(any());

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> runA = pool.submit(() -> useCase(Duration.ZERO).runOnce());
      assertThat(inCall.await(30, TimeUnit.SECONDS)).isTrue();
      useCase(QaRetryUseCaseLeases.REFUND.plusMinutes(1)).runOnce();
      release.countDown();
      runA.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // A's late result is fenced out: B holds the row, which stays pending
    assertThat(status(id)).isEqualTo("REFUND_PENDING");
    assertThat(
            jdbc.queryForObject(
                "select refunded_at is null from qa_reading where id = ?", Boolean.class, id))
        .isTrue();

    useCase(Duration.ofMinutes(5)).runOnce();

    verify(identity, times(3)).refund(eq(id));
    assertThat(status(id)).isEqualTo("REFUNDED");
  }

  /** The use case's lease lengths (package-private there). */
  static final class QaRetryUseCaseLeases {
    static final Duration RETRY = Duration.ofMinutes(10);
    static final Duration REFUND = Duration.ofMinutes(1);
  }
}
