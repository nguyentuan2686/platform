package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Public share links of a chart profile against real Postgres, with the internal token on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Import(ShareIT.RecordingMailer.class)
class ShareIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final String TOKEN = "local-test-internal-token";

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("platform.internal-token", () -> TOKEN);
  }

  static final List<URI> LINKS = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class RecordingMailer {
    @Bean
    @Primary
    dev.platform.identity.application.MailerPort recordingMailer() {
      return (to, link, lang) -> LINKS.add(link);
    }
  }

  static final String BODY =
      "{\"label\":\"Me\",\"birth\":{\"birth\":\"1994-03-12T05:30\",\"zone\":\"Asia/Ho_Chi_Minh\","
          + "\"gender\":\"female\"},\"calendar\":\"VN\"}";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> send(
      String method, String path, String bearer, String body, boolean internal) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (internal) {
      request.header("X-Internal-Token", TOKEN);
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String method, String path, String bearer, String body)
      throws Exception {
    return send(method, path, bearer, body, true);
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JsonMapper.shared().readTree(response.body());
  }

  private String guestToken() throws Exception {
    return json(send("POST", "/guests", null, null)).get("session_token").asString();
  }

  private String login(String email, String guestBearer) throws Exception {
    send("POST", "/auth/magic-links", guestBearer, "{\"email\":\"" + email + "\"}");
    String query = LINKS.get(LINKS.size() - 1).getRawQuery();
    String token = query.substring(query.indexOf("token=") + "token=".length());
    return json(send(
            "POST", "/auth/magic-links/verify", guestBearer, "{\"token\":\"" + token + "\"}"))
        .get("session_token")
        .asString();
  }

  @Test
  void guest_shares_the_profile_and_the_lookup_returns_the_decrypted_birth(CapturedOutput output)
      throws Exception {
    String guest = guestToken();

    HttpResponse<String> none = send("PUT", "/me/profile/share", guest, null);
    assertThat(none.statusCode()).isEqualTo(409);
    assertThat(json(none).get("reason").asString()).isEqualTo("no_profile");
    assertThat(send("GET", "/me/profile/share", guest, null).statusCode()).isEqualTo(404);

    send("PUT", "/me/profile", guest, BODY);
    JsonNode created = json(send("PUT", "/me/profile/share", guest, null));
    JsonNode again = json(send("PUT", "/me/profile/share", guest, null));
    JsonNode got = json(send("GET", "/me/profile/share", guest, null));
    String id = created.get("share_id").asString();

    assertThat(id).matches("[A-Za-z0-9_-]{22}");
    assertThat(again.get("share_id").asString()).isEqualTo(id);
    assertThat(got.get("share_id").asString()).isEqualTo(id);
    assertThat(created.get("created_at").asString()).isNotBlank();

    JsonNode shared = json(send("GET", "/shares/" + id, null, null));
    assertThat(shared.get("birth").get("birth").asString()).isEqualTo("1994-03-12T05:30");
    assertThat(shared.get("birth").get("zone").asString()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(shared.get("birth").get("gender").asString()).isEqualTo("female");
    assertThat(shared.get("calendar").asString()).isEqualTo("VN");

    assertThat(output.getAll())
        .as("no birth data in the logs")
        .doesNotContain("1994-03-12")
        .doesNotContain("05:30");
  }

  @Test
  void revoking_makes_the_lookup_404_and_a_new_link_gets_a_new_id() throws Exception {
    String guest = guestToken();
    send("PUT", "/me/profile", guest, BODY);
    String first = json(send("PUT", "/me/profile/share", guest, null)).get("share_id").asString();

    assertThat(send("DELETE", "/me/profile/share", guest, null).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", "/me/profile/share", guest, null).statusCode()).isEqualTo(204);

    assertThat(send("GET", "/shares/" + first, null, null).statusCode()).isEqualTo(404);
    assertThat(send("GET", "/me/profile/share", guest, null).statusCode()).isEqualTo(404);
    String second = json(send("PUT", "/me/profile/share", guest, null)).get("share_id").asString();
    assertThat(second).isNotEqualTo(first);
    assertThat(send("GET", "/shares/" + second, null, null).statusCode()).isEqualTo(200);
    assertThat(send("GET", "/shares/" + first, null, null).statusCode()).isEqualTo(404);
  }

  @Test
  void deleting_the_profile_removes_the_share() throws Exception {
    String guest = guestToken();
    send("PUT", "/me/profile", guest, BODY);
    String id = json(send("PUT", "/me/profile/share", guest, null)).get("share_id").asString();

    send("DELETE", "/me/profile", guest, null);

    assertThat(send("GET", "/shares/" + id, null, null).statusCode()).isEqualTo(404);
    assertThat(
            jdbc.queryForObject("select count(*) from profile_share where id = ?", Long.class, id))
        .isZero();
  }

  @Test
  void a_guest_merged_into_an_account_keeps_the_same_share() throws Exception {
    String guest = guestToken();
    send("PUT", "/me/profile", guest, BODY);
    String id = json(send("PUT", "/me/profile/share", guest, null)).get("share_id").asString();

    String account = login("share-merge@example.com", guest);

    assertThat(send("GET", "/shares/" + id, null, null).statusCode()).isEqualTo(200);
    assertThat(json(send("GET", "/me/profile/share", account, null)).get("share_id").asString())
        .isEqualTo(id);
  }

  @Test
  void the_lookup_needs_the_internal_token_and_rejects_malformed_ids() throws Exception {
    String guest = guestToken();
    send("PUT", "/me/profile", guest, BODY);
    String id = json(send("PUT", "/me/profile/share", guest, null)).get("share_id").asString();

    assertThat(send("GET", "/shares/" + id, null, null, false).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/shares/bad", null, null).statusCode()).isEqualTo(404);
    assertThat(send("GET", "/shares/" + "A".repeat(22), null, null).statusCode()).isEqualTo(404);
    assertThat(send("PUT", "/me/profile/share", null, null).statusCode()).isEqualTo(401);
  }
}
