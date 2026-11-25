package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Guest to account move of the Q&A history, against real Postgres. */
class QaMergeIT extends QaItSupport {

  private HttpResponse<String> merge(String body, boolean internal) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/qa/merge"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (internal) {
      b.header("X-Internal-Token", TOKEN);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String body(String guest, UUID account) {
    return JSON.createObjectNode()
        .put("guest_id", guest)
        .put("account_id", account.toString())
        .toString();
  }

  private UUID free(String question) throws Exception {
    UUID id = UUID.randomUUID();
    HttpResponse<String> res =
        send("POST", "/tuvi/qa/readings", ask(id, "FREE", "SuNghiep", question), GUEST_BEARER);
    assertThat(events(res.body()).getLast().name()).as(res.body()).isEqualTo("done");
    return id;
  }

  @Test
  void moves_the_guests_free_readings_to_the_account_once() throws Exception {
    UUID first = free("Công việc năm nay của tôi ra sao");
    UUID second = free("Sự nghiệp của tôi sang năm thế nào");
    assertThat(getJson("/tuvi/qa/readings", ACCOUNT_BEARER).get("readings")).isEmpty();

    HttpResponse<String> moved = merge(body(GUEST.toString(), ACCOUNT), true);
    HttpResponse<String> again = merge(body(GUEST.toString(), ACCOUNT), true);

    assertThat(moved.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(moved.body()).get("moved").asInt()).isEqualTo(2);
    assertThat(JSON.readTree(again.body()).get("moved").asInt()).isZero();
    JsonNode list = getJson("/tuvi/qa/readings", ACCOUNT_BEARER).get("readings");
    assertThat(list).hasSize(2);
    assertThat(list.findValuesAsString("reading_id"))
        .containsExactlyInAnyOrder(first.toString(), second.toString());
    assertThat(getJson("/tuvi/qa/readings", GUEST_BEARER).get("readings")).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from qa_reading where subject_kind = 'account' and subject_id = ?",
                Integer.class,
                ACCOUNT))
        .isEqualTo(2);
  }

  @Test
  void another_guests_readings_stay_where_they_are() throws Exception {
    UUID id = free("Công việc năm nay của tôi ra sao");

    HttpResponse<String> res = merge(body(UUID.randomUUID().toString(), ACCOUNT), true);

    assertThat(JSON.readTree(res.body()).get("moved").asInt()).isZero();
    assertThat(
            jdbc.queryForObject("select subject_id from qa_reading where id = ?", UUID.class, id))
        .isEqualTo(GUEST);
  }

  @Test
  void needs_the_internal_token() throws Exception {
    assertThat(merge(body(GUEST.toString(), ACCOUNT), false).statusCode()).isEqualTo(401);
  }

  @Test
  void a_malformed_id_is_a_validation_error() throws Exception {
    HttpResponse<String> res = merge(body("x", ACCOUNT), true);

    assertThat(res.statusCode()).isEqualTo(400);
    JsonNode problem = JSON.readTree(res.body());
    assertThat(problem.get("type").asString()).endsWith("validation-error");
    assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("guest_id");
    assertThat(merge("{}", true).statusCode()).isEqualTo(400);
  }
}
