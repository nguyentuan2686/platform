package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** A full paid + follow-up + free run: the logs carry ids only, never text or secrets. */
@ExtendWith(OutputCaptureExtension.class)
class NoPiiInLogsIT extends QaItSupport {

  static final String PAID_Q = "Chuyện tình duyên của tôi, email a.b@example.com, 0912345678";
  static final String FOLLOW_Q = "Vậy năm sau chuyện tình duyên thì sao";
  static final String FREE_Q = "Công việc năm nay của tôi ra sao, sinh 12/03/1994";

  @Test
  void logs_contain_no_question_answer_birth_bearer_or_keys(CapturedOutput output)
      throws Exception {
    UUID paid = UUID.randomUUID();
    List<String> answers = new ArrayList<>();
    answers.addAll(
        answers(
            send(
                "POST",
                "/tuvi/qa/readings",
                ask(paid, "PAID", "TinhDuyen", PAID_Q),
                ACCOUNT_BEARER)));
    String follow =
        JSON.createObjectNode()
            .put("message_id", UUID.randomUUID().toString())
            .put("question", FOLLOW_Q)
            .toString();
    answers.addAll(
        answers(send("POST", "/tuvi/qa/readings/" + paid + "/follow-ups", follow, ACCOUNT_BEARER)));
    answers.addAll(
        answers(
            send(
                "POST",
                "/tuvi/qa/readings",
                ask(UUID.randomUUID(), "FREE", "SuNghiep", FREE_Q),
                GUEST_BEARER)));
    send("GET", "/tuvi/qa/readings/" + paid, null, ACCOUNT_BEARER);
    assertThat(answers).hasSize(3).allMatch(a -> a.length() > 20);

    String logs = output.getAll();
    assertThat(logs)
        .doesNotContain(PAID_Q)
        .doesNotContain("a.b@example.com")
        .doesNotContain("0912345678")
        .doesNotContain(FOLLOW_Q)
        .doesNotContain(FREE_Q)
        .doesNotContain("12/03/1994")
        .doesNotContain("1994-03-12")
        .doesNotContain(ACCOUNT_BEARER)
        .doesNotContain(GUEST_BEARER)
        .doesNotContain(TOKEN)
        .doesNotContain("local-test-");
    for (String answer : answers) {
      assertThat(logs).doesNotContain(answer);
    }
  }

  static List<String> answers(HttpResponse<String> res) {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    StringBuilder sb = new StringBuilder();
    for (SseEvent e : events(res.body())) {
      if (e.name().equals("delta")) {
        sb.append(e.data().get("text").asString());
      }
    }
    assertThat(events(res.body()).getLast().name()).as(res.body()).isEqualTo("done");
    return List.of(sb.toString().strip());
  }
}
