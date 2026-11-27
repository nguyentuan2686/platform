package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.identity.application.MailerPort;
import dev.platform.identity.domain.Email;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MagicLinkFlowIT.RecordingMailer.class)
class MagicLinkFlowIT {

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

  record Sent(Email to, URI link) {}

  static final List<Sent> MAILS = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class RecordingMailer {
    @Bean
    @Primary
    MailerPort recordingMailer() {
      return (to, link, lang) -> MAILS.add(new Sent(to, link));
    }
  }

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

  @Test
  void guest_requests_a_link_verifies_it_and_becomes_an_account() throws Exception {
    HttpResponse<String> guest = send("POST", "/guests", null, null);
    assertThat(guest.statusCode()).isEqualTo(201);
    String guestToken = json(guest).get("session_token").asString();
    String guestId = json(guest).get("guest_id").asString();
    assertThat(json(send("GET", "/me", guestToken, null)).get("kind").asString())
        .isEqualTo("guest");

    int before = MAILS.size();
    HttpResponse<String> requested =
        send(
            "POST",
            "/auth/magic-links",
            guestToken,
            "{\"email\":\"Flow@Example.com\",\"lang\":\"vi\"}");
    assertThat(requested.statusCode()).isEqualTo(202);
    assertThat(MAILS).hasSize(before + 1);
    Sent mail = MAILS.get(MAILS.size() - 1);
    assertThat(mail.to().value()).isEqualTo("flow@example.com");
    assertThat(mail.link().getPath()).isEqualTo("/vi/dang-nhap/xac-nhan");
    String token = mail.link().getRawQuery().substring("token=".length());

    // Only a hash of the token is stored.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from magic_link where token_sha256 = decode(?, 'escape')",
                Long.class,
                token))
        .isZero();

    HttpResponse<String> verified =
        send("POST", "/auth/magic-links/verify", guestToken, "{\"token\":\"" + token + "\"}");
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(json(verified).get("created").asBoolean()).isTrue();
    String accountToken = json(verified).get("session_token").asString();

    JsonNode me = json(send("GET", "/me", accountToken, null));
    assertThat(me.get("kind").asString()).isEqualTo("account");
    assertThat(me.get("email_masked").asString()).isEqualTo("f***@e***.com");
    assertThat(send("GET", "/me", guestToken, null).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from session where guest_id = ?::uuid and revoked_at is null",
                Long.class,
                guestId))
        .isZero();

    // The link is single use.
    assertThat(
            send("POST", "/auth/magic-links/verify", null, "{\"token\":\"" + token + "\"}")
                .statusCode())
        .isEqualTo(401);

    // Logging out ends the account session.
    assertThat(send("DELETE", "/sessions/current", accountToken, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/me", accountToken, null).statusCode()).isEqualTo(401);
  }

  @Test
  void fourth_request_in_a_window_is_accepted_but_sends_no_mail() throws Exception {
    String body = "{\"email\":\"limit@example.com\",\"lang\":\"vi\"}";
    int before = MAILS.size();

    for (int i = 0; i < 4; i++) {
      assertThat(send("POST", "/auth/magic-links", null, body).statusCode()).isEqualTo(202);
    }

    assertThat(MAILS).hasSize(before + 3);
  }

  @Test
  void second_login_with_the_same_email_reuses_the_account() throws Exception {
    String body = "{\"email\":\"again@example.com\",\"lang\":\"vi\"}";
    String ids[] = new String[2];
    for (int i = 0; i < 2; i++) {
      send("POST", "/auth/magic-links", null, body);
      String token = MAILS.get(MAILS.size() - 1).link().getRawQuery().substring("token=".length());
      JsonNode login =
          json(send("POST", "/auth/magic-links/verify", null, "{\"token\":\"" + token + "\"}"));
      ids[i] = login.get("account_id").asString();
      assertThat(login.get("created").asBoolean()).isEqualTo(i == 0);
    }

    assertThat(ids[1]).isEqualTo(ids[0]);
  }

  @Test
  void parallel_requests_for_one_email_cannot_exceed_the_rate_limit() throws Exception {
    String body = "{\"email\":\"race@example.com\",\"lang\":\"vi\"}";
    int before = MAILS.size();
    int n = 12;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
    var start = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return send("POST", "/auth/magic-links", null, body).statusCode();
              }));
    }
    start.countDown();
    for (var f : results) {
      assertThat(f.get()).isEqualTo(202);
    }
    pool.shutdown();

    assertThat(MAILS).hasSize(before + 3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from magic_link where email = 'race@example.com'", Long.class))
        .isEqualTo(3L);
  }

  @Test
  void daily_mail_cap_over_all_addresses_answers_429_and_sends_nothing() throws Exception {
    // 200 links (MAIL_DAILY_CAP default) already sent today to other addresses, straight in the db.
    jdbc.update(
        "insert into magic_link (id, email, token_sha256, created_at, expires_at)"
            + " select gen_random_uuid(), 'cap-fill-' || i || '@example.com',"
            + " sha256(('cap-fill-' || i)::bytea), now(), now() + interval '15 minutes'"
            + " from generate_series(1, 200) i");
    int before = MAILS.size();
    try {
      HttpResponse<String> refused =
          send(
              "POST", "/auth/magic-links", null, "{\"email\":\"cap@example.com\",\"lang\":\"vi\"}");

      assertThat(refused.statusCode()).isEqualTo(429);
      assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
      assertThat(json(refused).path("reason").asString()).isEqualTo("mail_daily_cap");
      assertThat(refused.body()).doesNotContain("cap@example.com");
      assertThat(MAILS).hasSize(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from magic_link where email = 'cap@example.com'", Long.class))
          .isZero();
    } finally {
      jdbc.update("delete from magic_link where email like 'cap-fill-%'");
    }
  }
}
