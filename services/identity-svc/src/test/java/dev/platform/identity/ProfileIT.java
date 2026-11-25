package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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

/** Encrypted profiles, the birth lock and the guest merge against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProfileIT.RecordingMailer.class)
class ProfileIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static final java.util.List<URI> LINKS = new java.util.concurrent.CopyOnWriteArrayList<>();

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
  static final String OTHER_BODY = BODY.replace("1994-03-12", "1990-01-01");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> send(String method, String path, String bearer, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JsonMapper.shared().readTree(response.body());
  }

  private String guestToken() throws Exception {
    return json(send("POST", "/guests", null, null)).get("session_token").asString();
  }

  /** Logs the guest in by magic link; returns the account session token. */
  private JsonNode login(String email, String guestBearer) throws Exception {
    send("POST", "/auth/magic-links", guestBearer, "{\"email\":\"" + email + "\"}");
    String query = LINKS.get(LINKS.size() - 1).getRawQuery();
    String token = query.substring(query.indexOf("token=") + "token=".length());
    return json(
        send("POST", "/auth/magic-links/verify", guestBearer, "{\"token\":\"" + token + "\"}"));
  }

  @Test
  void put_get_delete_over_http_and_the_database_holds_no_plaintext_birth() throws Exception {
    String guest = guestToken();

    assertThat(send("GET", "/me/profile", guest, null).statusCode()).isEqualTo(404);
    HttpResponse<String> put = send("PUT", "/me/profile", guest, BODY);
    JsonNode got = json(send("GET", "/me/profile", guest, null));

    assertThat(put.statusCode()).isEqualTo(200);
    assertThat(got.get("label").asString()).isEqualTo("Me");
    assertThat(got.get("birth").get("birth").asString()).isEqualTo("1994-03-12T05:30");
    assertThat(got.get("birth").get("gender").asString()).isEqualTo("female");
    assertThat(got.get("calendar").asString()).isEqualTo("VN");
    assertThat(got.get("locked").asBoolean()).isFalse();
    byte[] enc =
        jdbc.queryForObject(
            "select birth_enc from chart_profile where id = ?::uuid",
            byte[].class,
            got.get("id").asString());
    assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("1994-03-12")
        .doesNotContain("female");
    assertThat(
            jdbc.queryForObject(
                "select key_id from chart_profile where id = ?::uuid",
                Integer.class,
                got.get("id").asString()))
        .isEqualTo(1);

    assertThat(send("PUT", "/me/profile", guest, OTHER_BODY).statusCode()).isEqualTo(200);
    assertThat(json(send("GET", "/me/profile", guest, null)).get("birth").get("birth").asString())
        .isEqualTo("1990-01-01T05:30");
    assertThat(send("DELETE", "/me/profile", guest, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/me/profile", guest, null).statusCode()).isEqualTo(404);
  }

  @Test
  void unauthenticated_and_invalid_requests_are_rejected_without_echoing_birth() throws Exception {
    String guest = guestToken();

    assertThat(send("GET", "/me/profile", null, null).statusCode()).isEqualTo(401);
    HttpResponse<String> invalid =
        send("PUT", "/me/profile", guest, BODY.replace("1994-03-12", "1994-13-45"));

    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(invalid.body()).doesNotContain("1994");
  }

  @Test
  void a_locked_account_cannot_change_birth_and_the_lock_survives_delete() throws Exception {
    JsonNode session = login("lock@example.com", null);
    String bearer = session.get("session_token").asString();
    UUID account = UUID.fromString(session.get("account_id").asString());
    assertThat(send("PUT", "/me/profile", bearer, BODY).statusCode()).isEqualTo(200);
    jdbc.update("update account set birth_locked_at = now() where id = ?", account);

    HttpResponse<String> changed = send("PUT", "/me/profile", bearer, OTHER_BODY);
    HttpResponse<String> relabelled =
        send("PUT", "/me/profile", bearer, BODY.replace("\"Me\"", "\"Mom\"").replace("VN", "CN"));

    assertThat(changed.statusCode()).isEqualTo(409);
    assertThat(json(changed).get("reason").asString()).isEqualTo("profile_locked");
    assertThat(changed.body()).doesNotContain("1990").doesNotContain("1994");
    assertThat(relabelled.statusCode()).isEqualTo(200);
    assertThat(json(relabelled).get("locked").asBoolean()).isTrue();

    assertThat(send("DELETE", "/me/profile", bearer, null).statusCode()).isEqualTo(204);
    assertThat(send("PUT", "/me/profile", bearer, OTHER_BODY).statusCode()).isEqualTo(200);
    assertThat(send("PUT", "/me/profile", bearer, BODY).statusCode()).isEqualTo(409);
  }

  @Test
  void login_moves_the_guest_profile_to_an_account_without_one() throws Exception {
    String guest = guestToken();
    String profileId = json(send("PUT", "/me/profile", guest, BODY)).get("id").asString();

    JsonNode session = login("mover@example.com", guest);
    String bearer = session.get("session_token").asString();

    JsonNode moved = json(send("GET", "/me/profile", bearer, null));
    assertThat(moved.get("id").asString()).isEqualTo(profileId);
    assertThat(moved.get("birth").get("birth").asString()).isEqualTo("1994-03-12T05:30");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from chart_profile where account_id = ?::uuid and guest_id is null",
                Long.class,
                session.get("account_id").asString()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from guest where merged_into = ?::uuid",
                Long.class,
                session.get("account_id").asString()))
        .isEqualTo(1L);
  }

  @Test
  void login_keeps_the_account_profile_and_deletes_the_guest_one() throws Exception {
    JsonNode first = login("keeper@example.com", null);
    String accountBearer = first.get("session_token").asString();
    send("PUT", "/me/profile", accountBearer, OTHER_BODY.replace("\"Me\"", "\"Account\""));
    String guest = guestToken();
    send("PUT", "/me/profile", guest, BODY.replace("\"Me\"", "\"Guest\""));

    JsonNode second = login("keeper@example.com", guest);

    JsonNode kept = json(send("GET", "/me/profile", second.get("session_token").asString(), null));
    assertThat(kept.get("label").asString()).isEqualTo("Account");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from chart_profile where label = 'Guest'", Long.class))
        .isZero();
    assertThat(send("GET", "/me/profile", guest, null).statusCode()).isEqualTo(401);
  }
}
