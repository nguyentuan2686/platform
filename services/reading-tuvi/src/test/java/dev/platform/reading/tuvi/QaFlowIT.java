package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class QaFlowIT extends QaItSupport {

  static final String QUESTION = "Năm nay chuyện tình duyên của tôi thế nào";

  List<SseEvent> paid(UUID id) throws Exception {
    HttpResponse<String> res =
        send("POST", "/tuvi/qa/readings", ask(id, "PAID", "TinhDuyen", QUESTION), ACCOUNT_BEARER);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
    return events(res.body());
  }

  @Test
  void paid_stream_matches_history_and_question_is_encrypted() throws Exception {
    UUID id = UUID.randomUUID();

    List<SseEvent> events = paid(id);

    List<String> names = events.stream().map(SseEvent::name).toList();
    assertThat(names.getFirst()).isEqualTo("meta");
    assertThat(names.getLast()).isEqualTo("done");
    assertThat(names.subList(1, names.size() - 1)).isNotEmpty().allMatch("delta"::equals);
    assertThat(events.getFirst().data().get("follow_ups_left").asInt()).isEqualTo(2);
    String streamed =
        events.stream()
            .filter(e -> e.name().equals("delta"))
            .map(e -> e.data().get("text").asString())
            .collect(Collectors.joining());
    JsonNode done = events.getLast().data();

    JsonNode reading = getJson("/tuvi/qa/readings/" + id, ACCOUNT_BEARER);
    assertThat(reading.get("status").asString()).isEqualTo("ANSWERED");
    assertThat(reading.get("messages").get(0).get("answer").asString()).isEqualTo(streamed);
    assertThat(reading.get("messages").get(0).get("question").asString()).isEqualTo(QUESTION);
    assertThat(reading.get("disclaimer").asString())
        .isEqualTo(done.get("disclaimer").asString())
        .startsWith("Lời ta nói");
    verify(identity, times(1)).consume(eq(ACCOUNT), eq(id), eq(QaTopic.TinhDuyen));

    byte[] stored =
        jdbc.queryForObject(
            "select question_enc from qa_message where reading_id = ?", byte[].class, id);
    assertThat(new String(stored, StandardCharsets.ISO_8859_1))
        .doesNotContain(
            new String(QUESTION.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    byte[] answer =
        jdbc.queryForObject(
            "select answer_enc from qa_message where reading_id = ?", byte[].class, id);
    assertThat(new String(answer, StandardCharsets.UTF_8)).doesNotContain("Con hỏi về");

    // the same id again replays without a second charge
    List<SseEvent> again = paid(id);
    assertThat(again.getLast().name()).isEqualTo("done");
    verify(identity, times(1)).consume(any(), any(), any());
  }

  @Test
  void concurrent_same_reading_id_one_generation() throws Exception {
    UUID id = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    Callable<HttpResponse<String>> call =
        () -> {
          start.await();
          return send(
              "POST", "/tuvi/qa/readings", ask(id, "PAID", "TinhDuyen", QUESTION), ACCOUNT_BEARER);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      futures.add(pool.submit(call));
      futures.add(pool.submit(call));
      start.countDown();
      int done = 0;
      for (Future<HttpResponse<String>> f : futures) {
        HttpResponse<String> res = f.get();
        if (res.statusCode() == 200) {
          assertThat(events(res.body()).getLast().name()).isEqualTo("done");
          done++;
        } else {
          assertThat(res.statusCode()).isEqualTo(409);
          assertThat(JSON.readTree(res.body()).get("reason").asString())
              .isEqualTo("reading_in_progress");
        }
      }
      assertThat(done).isGreaterThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    verify(identity, times(1)).consume(any(), any(), any());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from qa_message where reading_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void two_follow_ups_then_409() throws Exception {
    UUID id = UUID.randomUUID();
    paid(id);

    for (int i = 1; i <= 2; i++) {
      String body =
          JSON.createObjectNode()
              .put("message_id", UUID.randomUUID().toString())
              .put("question", "Vậy năm sau chuyện tình duyên thì sao " + i)
              .toString();
      HttpResponse<String> res =
          send("POST", "/tuvi/qa/readings/" + id + "/follow-ups", body, ACCOUNT_BEARER);
      assertThat(res.statusCode()).isEqualTo(200);
      List<SseEvent> events = events(res.body());
      assertThat(events.getLast().name()).as(res.body()).isEqualTo("done");
      assertThat(events.getLast().data().get("seq").asInt()).isEqualTo(i);
    }
    String third =
        JSON.createObjectNode()
            .put("message_id", UUID.randomUUID().toString())
            .put("question", "Thêm một câu nữa nhé")
            .toString();
    HttpResponse<String> res =
        send("POST", "/tuvi/qa/readings/" + id + "/follow-ups", third, ACCOUNT_BEARER);

    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(res.body()).get("reason").asString())
        .isEqualTo("follow_ups_exhausted");
    JsonNode reading = getJson("/tuvi/qa/readings/" + id, ACCOUNT_BEARER);
    assertThat(reading.get("messages")).hasSize(3);
    assertThat(reading.get("follow_ups_left").asInt()).isZero();
    verify(identity, times(1)).consume(any(), any(), any());
  }

  @Test
  void delete_all_empties_the_list() throws Exception {
    paid(UUID.randomUUID());
    HttpResponse<String> free =
        send(
            "POST",
            "/tuvi/qa/readings",
            ask(UUID.randomUUID(), "FREE", "SuNghiep", "Công việc năm nay của tôi ra sao"),
            ACCOUNT_BEARER);
    assertThat(events(free.body()).getLast().name()).as(free.body()).isEqualTo("done");
    assertThat(getJson("/tuvi/qa/readings", ACCOUNT_BEARER).get("readings")).hasSize(2);
    assertThat(getJson("/tuvi/qa/free-quota", ACCOUNT_BEARER).get("used").asInt()).isEqualTo(1);

    assertThat(send("DELETE", "/tuvi/qa/readings", null, ACCOUNT_BEARER).statusCode())
        .isEqualTo(204);

    assertThat(getJson("/tuvi/qa/readings", ACCOUNT_BEARER).get("readings")).isEmpty();
  }

  @Test
  void deleted_paid_reading_id_reused_is_409_without_a_second_consume() throws Exception {
    UUID id = UUID.randomUUID();
    paid(id);
    assertThat(send("DELETE", "/tuvi/qa/readings", null, ACCOUNT_BEARER).statusCode())
        .isEqualTo(204);

    HttpResponse<String> res =
        send(
            "POST",
            "/tuvi/qa/readings",
            ask(id, "PAID", "SuNghiep", "Công việc năm nay của tôi ra sao"),
            ACCOUNT_BEARER);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
    assertThat(JSON.readTree(res.body()).get("reason").asString()).isEqualTo("reading_conflict");
    verify(identity, times(1)).consume(any(), any(), any());
    assertThat(
            jdbc.queryForObject("select count(*) from qa_reading where id = ?", Integer.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from qa_deleted_reading where id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void qa_requires_the_internal_token() throws Exception {
    var res =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/tuvi/qa/readings"))
                    .header("Authorization", "Bearer " + ACCOUNT_BEARER)
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

    assertThat(res.statusCode()).isEqualTo(401);
  }
}
