package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.platform.identity.support.FakeIdp;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Google login against real Postgres and the in-test fake provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoogleLoginFlowIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final FakeIdp IDP = new FakeIdp();
  static final String CLIENT = "local-test-client";
  static final String REDIRECT = "http://localhost:3001/vi/dang-nhap/google";

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("identity.oidc.google.issuer", () -> IDP.issuer().toString());
    registry.add("identity.oidc.google.client-id", () -> CLIENT);
    registry.add("identity.oidc.google.client-secret", () -> "local-test-secret");
    registry.add("identity.oidc.google.redirect-uris", () -> REDIRECT);
  }

  @AfterAll
  static void stopIdp() {
    IDP.close();
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JsonMapper.shared().readTree(response.body());
  }

  private String authorize() throws Exception {
    HttpResponse<String> response =
        send(
            "GET",
            "/auth/google/authorization?redirect_uri="
                + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8),
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    return json(response).get("state").asString();
  }

  private String nonceOf(String state) {
    return jdbc.queryForObject("select nonce from oidc_login where state = ?", String.class, state);
  }

  private void idpIssues(String sub, String email, String nonce) {
    JWTClaimsSet claims =
        IDP.validClaims(CLIENT, nonce, new Date()).subject(sub).claim("email", email).build();
    IDP.respondWithIdToken(FakeIdp.sign(FakeIdp.KEY, claims));
  }

  private HttpResponse<String> callback(String state) throws Exception {
    return send("POST", "/auth/google/callback", "{\"code\":\"c\",\"state\":\"" + state + "\"}");
  }

  /** A full login: authorization, provider issues a token for the nonce, callback. */
  private HttpResponse<String> login(String sub, String email) throws Exception {
    String state = authorize();
    idpIssues(sub, email, nonceOf(state));
    return callback(state);
  }

  @Test
  void state_is_stored_and_a_second_callback_with_the_same_state_is_401() throws Exception {
    String state = authorize();
    idpIssues("sub-single", "single@example.com", nonceOf(state));

    HttpResponse<String> first = callback(state);
    HttpResponse<String> second = callback(state);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(json(first).get("created").asBoolean()).isTrue();
    assertThat(second.statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select used_at is not null from oidc_login where state = ?", Boolean.class, state))
        .isTrue();
  }

  @Test
  void state_is_burned_by_a_failed_callback() throws Exception {
    String state = authorize();
    idpIssues("sub-burn", "burn@example.com", "not-the-nonce");

    assertThat(callback(state).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select used_at is not null from oidc_login where state = ?", Boolean.class, state))
        .isTrue();

    // Even a now-correct token cannot reuse it, and nothing was created.
    idpIssues("sub-burn", "burn@example.com", nonceOf(state));
    assertThat(callback(state).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account where email = 'burn@example.com'", Long.class))
        .isZero();
  }

  @Test
  void state_is_burned_when_the_provider_rejects_the_code() throws Exception {
    String state = authorize();
    IDP.respondWithStatus(400);
    try {
      assertThat(callback(state).statusCode()).isEqualTo(401);
    } finally {
      IDP.respondWithStatus(200);
    }
    idpIssues("sub-rej", "rejected@example.com", nonceOf(state));

    assertThat(callback(state).statusCode()).isEqualTo(401);
  }

  @Test
  void expired_state_is_401_and_the_provider_is_not_called() throws Exception {
    String state = authorize();
    jdbc.update(
        "update oidc_login set expires_at = now() - interval '1 minute' where state = ?", state);
    idpIssues("sub-exp", "expired@example.com", nonceOf(state));
    int before = IDP.tokenRequests().size();

    assertThat(callback(state).statusCode()).isEqualTo(401);
    assertThat(IDP.tokenRequests()).hasSize(before);
  }

  @Test
  void unknown_state_is_401() throws Exception {
    assertThat(callback("never-issued").statusCode()).isEqualTo(401);
  }

  @Test
  void returning_google_user_is_found_by_sub_without_a_second_account() throws Exception {
    HttpResponse<String> first = login("sub-return", "return@example.com");
    HttpResponse<String> second = login("sub-return", "return@example.com");

    assertThat(json(first).get("created").asBoolean()).isTrue();
    assertThat(json(second).get("created").asBoolean()).isFalse();
    assertThat(json(second).get("account_id").asString())
        .isEqualTo(json(first).get("account_id").asString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account where google_sub = 'sub-return'", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void existing_email_account_is_linked_to_the_google_subject() throws Exception {
    UUID existing = UUID.randomUUID();
    jdbc.update("insert into account (id, email) values (?, 'linked@example.com')", existing);

    HttpResponse<String> response = login("sub-linked", "linked@example.com");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("created").asBoolean()).isFalse();
    assertThat(json(response).get("account_id").asString()).isEqualTo(existing.toString());
    assertThat(
            jdbc.queryForObject(
                "select google_sub from account where id = ?", String.class, existing))
        .isEqualTo("sub-linked");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account where email = 'linked@example.com'", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void email_account_linked_to_another_subject_is_not_taken_over() throws Exception {
    UUID existing = UUID.randomUUID();
    jdbc.update(
        "insert into account (id, email, google_sub) values (?, 'owned@example.com', 'sub-owner')",
        existing);

    HttpResponse<String> response = login("sub-intruder", "owned@example.com");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select google_sub from account where id = ?", String.class, existing))
        .isEqualTo("sub-owner");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account where google_sub = 'sub-intruder'", Long.class))
        .isZero();
  }

  @Test
  void unverified_email_is_401_and_creates_no_account() throws Exception {
    String state = authorize();
    JWTClaimsSet claims =
        IDP.validClaims(CLIENT, nonceOf(state), new Date())
            .subject("sub-unverified")
            .claim("email", "unverified@example.com")
            .claim("email_verified", false)
            .build();
    IDP.respondWithIdToken(FakeIdp.sign(FakeIdp.KEY, claims));

    assertThat(callback(state).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account where email = 'unverified@example.com'", Long.class))
        .isZero();
  }
}
