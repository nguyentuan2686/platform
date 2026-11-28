package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** POST /tuvi/number-check end to end: real Postgres quota and knowledge, identity mocked. */
class NumberCheckControllerIT extends QaItSupport {

  static final String SIM = "0912345678";

  @BeforeEach
  void cleanUsage() {
    jdbc.update("delete from number_check_usage");
  }

  static String body(String kind, String vehicle, String number, Object year, String gender) {
    var n = JSON.createObjectNode().put("kind", kind).put("number", number).put("beforeTet", false);
    if (vehicle != null) {
      n.put("vehicle", vehicle);
    }
    if (year instanceof Integer y) {
      n.put("birthYear", y);
    } else if (year != null) {
      n.put("birthYear", year.toString());
    }
    if (gender != null) {
      n.put("gender", gender);
    }
    return n.toString();
  }

  HttpResponse<String> post(String body, String bearer, boolean token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/tuvi/number-check"))
            .header("Authorization", "Bearer " + bearer)
            .header("X-Client-Ip", "203.0.113.7")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token) {
      b.header("X-Internal-Token", TOKEN);
    }
    return HttpClient.newHttpClient()
        .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void golden_sim_returns_score_criteria_and_reading_but_not_the_number() throws Exception {
    HttpResponse<String> res = post(body("sim", null, SIM, 1990, "male"), GUEST_BEARER, true);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode j = JSON.readTree(res.body());
    assertThat(j.get("score").asDouble()).isEqualTo(7.0);
    assertThat(j.get("band").asString()).isEqualTo("kha_hop");
    assertThat(j.get("bandLabel").asString()).isEqualTo("Khá hợp");
    assertThat(j.get("menh").get("napAm").asString()).isEqualTo("Lộ Bàng Thổ");
    assertThat(j.get("menh").get("canChi").asString()).isEqualTo("Canh Ngọ");
    assertThat(j.get("cungPhi").get("name").asString()).isEqualTo("Khảm");
    assertThat(j.get("cungPhi").get("group").asString()).isEqualTo("Đông tứ mệnh");
    assertThat(j.get("criteria")).hasSize(6);
    assertThat(j.get("reading").size()).isEqualTo(10);
    assertThat(j.get("quota").get("used").asInt()).isEqualTo(1);
    assertThat(j.get("quota").get("remaining").asInt()).isEqualTo(1);
    assertThat(res.body()).doesNotContain(SIM).doesNotContain("912345678");
    assertThat(ContractSupport.componentSchema("NumberCheckResult").validate(j)).isEmpty();

    JsonNode quota = getJson("/tuvi/number-check/quota", GUEST_BEARER);
    assertThat(ContractSupport.componentSchema("NumberCheckQuota").validate(quota)).isEmpty();
    assertThat(quota.get("used").asInt()).isEqualTo(1);
  }

  @Test
  void third_distinct_number_is_409_and_the_first_is_still_free() throws Exception {
    assertThat(post(body("sim", null, SIM, 1990, "male"), GUEST_BEARER, true).statusCode())
        .isEqualTo(200);
    assertThat(post(body("bank", null, "123456789", 1990, "male"), GUEST_BEARER, true).statusCode())
        .isEqualTo(200);
    HttpResponse<String> third =
        post(body("plate", "car", "30A-123.45", 1990, "female"), GUEST_BEARER, true);
    assertThat(third.statusCode()).isEqualTo(409);
    assertThat(third.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    JsonNode p = JSON.readTree(third.body());
    assertThat(p.get("reason").asString()).isEqualTo("number_quota");
    assertThat(p.get("quota").get("remaining").asInt()).isZero();
    assertThat(p.get("trace_id")).isNotNull();
    assertThat(ContractSupport.componentSchema("Problem").validate(p)).isEmpty();

    // same number, other spelling, other birth data: free
    HttpResponse<String> again =
        post(body("sim", null, "+84 912 345 678", 1984, "female"), GUEST_BEARER, true);
    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    assertThat(JSON.readTree(again.body()).get("quota").get("used").asInt()).isEqualTo(2);
  }

  @Test
  void bad_input_is_400_with_a_reason_and_costs_nothing() throws Exception {
    for (String[] c :
        new String[][] {
          {body("sim", null, "12345", 1990, "male"), "invalid_number"},
          {body("plate", null, "30A12345", 1990, "male"), "invalid_number"},
          {body("phone", null, SIM, 1990, "male"), "invalid_number"},
          {body("sim", null, SIM, 1929, "male"), "invalid_birth"},
          {body("sim", null, SIM, null, "male"), "invalid_birth"},
          {body("sim", null, SIM, 1990, null), "invalid_birth"},
        }) {
      HttpResponse<String> res = post(c[0], GUEST_BEARER, true);
      assertThat(res.statusCode()).as(c[1]).isEqualTo(400);
      JsonNode p = JSON.readTree(res.body());
      assertThat(p.get("reason").asString()).isEqualTo(c[1]);
      assertThat(ContractSupport.componentSchema("Problem").validate(p)).isEmpty();
      assertThat(res.body()).doesNotContain("12345").doesNotContain(SIM);
    }
    HttpResponse<String> notANumber =
        post(body("sim", null, SIM, "abc", "male"), GUEST_BEARER, true);
    assertThat(notANumber.statusCode()).isEqualTo(400);
    assertThat(getJson("/tuvi/number-check/quota", GUEST_BEARER).get("used").asInt()).isZero();
  }

  @Test
  void missing_internal_token_is_401() throws Exception {
    assertThat(post(body("sim", null, SIM, 1990, "male"), GUEST_BEARER, false).statusCode())
        .isEqualTo(401);
  }
}
