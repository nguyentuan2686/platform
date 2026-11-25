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
import dev.platform.reading.tuvi.application.llm.AllProvidersExhaustedException;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.qa.QaRetentionUseCase;
import dev.platform.reading.tuvi.application.qa.QaRetryUseCase;
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
}
