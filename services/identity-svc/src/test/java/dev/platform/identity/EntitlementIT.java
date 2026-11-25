package dev.platform.identity;

import static dev.platform.identity.support.HttpIdentity.PROFILE_BODY;
import static dev.platform.identity.support.HttpIdentity.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.identity.support.HttpIdentity;
import dev.platform.identity.support.HttpIdentity.Login;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
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

/** The ledger, the internal credit API and the signup bonus against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EntitlementIT.RecordingMailer.class)
class EntitlementIT {

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

  @TestConfiguration
  static class RecordingMailer {
    @Bean
    @Primary
    dev.platform.identity.application.MailerPort recordingMailer() {
      return (to, link, lang) -> HttpIdentity.LINKS.add(link);
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  HttpIdentity api;

  @BeforeEach
  void client() {
    api = new HttpIdentity(port);
  }

  private Login newAccountWithProfile() throws Exception {
    Login login = api.login("user-" + UUID.randomUUID() + "@example.org");
    assertThat(api.send("PUT", "/me/profile", login.token(), PROFILE_BODY).statusCode())
        .isEqualTo(200);
    return login;
  }

  @Test
  void signup_bonus_is_one_credit_once_and_shows_in_the_ledger() throws Exception {
    String email = "bonus-" + UUID.randomUUID() + "@example.org";
    Login first = api.login(email);
    Login again = api.login(email);

    assertThat(again.accountId()).isEqualTo(first.accountId());
    JsonNode entitlements = json(api.send("GET", "/me/entitlements", first.token(), null));
    assertThat(entitlements.get("credits").asInt()).isEqualTo(1);
    assertThat(entitlements.get("pass").isNull()).isTrue();
    assertThat(entitlements.get("features")).isEmpty();
    JsonNode ledger = json(api.send("GET", "/me/ledger", first.token(), null));
    assertThat(ledger).hasSize(1);
    assertThat(ledger.get(0).get("reason").asString()).isEqualTo("signup_bonus");
    assertThat(ledger.get(0).get("delta").asInt()).isEqualTo(1);
  }

  @Test
  void a_guest_has_no_entitlements() throws Exception {
    assertThat(api.send("GET", "/me/entitlements", api.guestToken(), null).statusCode())
        .isEqualTo(403);
    assertThat(api.send("GET", "/me/ledger", api.guestToken(), null).statusCode()).isEqualTo(403);
    assertThat(api.send("GET", "/me/entitlements", null, null).statusCode()).isEqualTo(401);
  }

  @Test
  void concurrent_consume_of_last_credit() throws Exception {
    for (int round = 0; round < 5; round++) {
      Login login = newAccountWithProfile();
      assertThat(api.credits(login)).isEqualTo(1);
      int threads = 4;
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<HttpResponse<String>>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return api.consume(login.accountId(), UUID.randomUUID(), "TaiLoc");
                }));
      }
      ready.await();
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<HttpResponse<String>> f : results) {
        HttpResponse<String> response = f.get();
        statuses.add(response.statusCode());
        if (response.statusCode() == 409) {
          assertThat(json(response).get("reason").asString()).isEqualTo("no_credits");
        }
      }
      pool.shutdown();

      assertThat(statuses).containsOnlyOnce(200).filteredOn(s -> s != 200).containsOnly(409);
      assertThat(api.credits(login)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select coalesce(sum(delta), 0) from credit_ledger where account_id = ?::uuid",
                  Integer.class,
                  login.accountId().toString()))
          .isZero();
    }
  }

  @Test
  void consume_is_idempotent_locks_the_birth_and_refund_returns_the_credit() throws Exception {
    Login login = newAccountWithProfile();
    UUID reading = UUID.randomUUID();

    HttpResponse<String> first = api.consume(login.accountId(), reading, "SuNghiep");
    HttpResponse<String> repeat = api.consume(login.accountId(), reading, "SuNghiep");

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(json(first).get("source").asString()).isEqualTo("CREDIT");
    assertThat(json(first).get("credits_left").asInt()).isZero();
    assertThat(repeat.statusCode()).isEqualTo(200);
    assertThat(json(repeat).get("credits_left").asInt()).isZero();
    assertThat(api.credits(login)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select birth_locked_at is not null from account where id = ?::uuid",
                Boolean.class,
                login.accountId().toString()))
        .isTrue();
    String changed = PROFILE_BODY.replace("1994-03-12", "1990-01-01");
    HttpResponse<String> locked = api.send("PUT", "/me/profile", login.token(), changed);
    assertThat(locked.statusCode()).isEqualTo(409);
    assertThat(json(locked).get("reason").asString()).isEqualTo("profile_locked");

    String refundBody = "{\"reading_id\":\"" + reading + "\"}";
    assertThat(api.send("POST", "/internal/credits/refund", null, refundBody).statusCode())
        .isEqualTo(200);
    assertThat(api.send("POST", "/internal/credits/refund", null, refundBody).statusCode())
        .isEqualTo(200);
    assertThat(api.credits(login)).isEqualTo(1);
    HttpResponse<String> reused = api.consume(login.accountId(), reading, "SuNghiep");
    assertThat(reused.statusCode()).isEqualTo(409);
    assertThat(json(reused).get("reason").asString()).isEqualTo("reading_refunded");
    assertThat(api.credits(login)).isEqualTo(1);
    assertThat(
            api.send(
                    "POST",
                    "/internal/credits/refund",
                    null,
                    "{\"reading_id\":\"" + UUID.randomUUID() + "\"}")
                .statusCode())
        .isEqualTo(404);
    JsonNode ledger = json(api.send("GET", "/me/ledger", login.token(), null));
    assertThat(ledger.get(0).get("reason").asString()).isEqualTo("refund");
    assertThat(ledger.get(0).get("topic").asString()).isEqualTo("SuNghiep");
  }

  @Test
  void consume_without_a_profile_is_409_no_profile() throws Exception {
    Login login = api.login("noprofile-" + UUID.randomUUID() + "@example.org");

    HttpResponse<String> response = api.consume(login.accountId(), UUID.randomUUID(), "TaiLoc");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json(response).get("reason").asString()).isEqualTo("no_profile");
    assertThat(api.credits(login)).isEqualTo(1);
  }

  @Test
  void consume_for_an_unknown_account_is_404() throws Exception {
    assertThat(api.consume(UUID.randomUUID(), UUID.randomUUID(), "TaiLoc").statusCode())
        .isEqualTo(404);
  }

  @Test
  void reversal_takes_credits_back_once_and_refuses_when_spent() throws Exception {
    Login login = newAccountWithProfile();
    UUID order = UUID.randomUUID();
    jdbc.update(
        "insert into credit_ledger (account_id, delta, source, reason, ref)"
            + " values (?::uuid, 5, 'CREDIT', 'purchase', ?)",
        login.accountId().toString(),
        order.toString());
    String body =
        "{\"account_id\":\""
            + login.accountId()
            + "\",\"order_id\":\""
            + order
            + "\",\"credits\":5}";

    assertThat(api.send("POST", "/internal/credits/reversals", null, body).statusCode())
        .isEqualTo(200);
    assertThat(api.send("POST", "/internal/credits/reversals", null, body).statusCode())
        .isEqualTo(200);
    assertThat(api.credits(login)).isEqualTo(1);

    HttpResponse<String> spent =
        api.send(
            "POST",
            "/internal/credits/reversals",
            null,
            body.replace(order.toString(), UUID.randomUUID().toString()));
    assertThat(spent.statusCode()).isEqualTo(409);
    assertThat(json(spent).get("reason").asString()).isEqualTo("insufficient_credits");
    assertThat(api.credits(login)).isEqualTo(1);
  }

  @Test
  void a_year_pass_is_spent_before_credits_and_capped_at_30_a_day() throws Exception {
    Login login = newAccountWithProfile();
    jdbc.update(
        "insert into pass (id, account_id, kind, order_id, starts_at, ends_at)"
            + " values (gen_random_uuid(), ?::uuid, 'YEAR', gen_random_uuid(),"
            + " now() - interval '1 hour', now() + interval '300 days')",
        login.accountId().toString());

    JsonNode view = json(api.send("GET", "/me/entitlements", login.token(), null));
    assertThat(view.get("pass").get("kind").asString()).isEqualTo("YEAR");
    assertThat(view.get("pass").get("questions_left").isNull()).isTrue();
    assertThat(view.get("features")).hasSize(2);

    int ok = 0;
    int capped = 0;
    for (int i = 0; i < 32; i++) {
      HttpResponse<String> r = api.consume(login.accountId(), UUID.randomUUID(), "VanNam");
      if (r.statusCode() == 200) {
        assertThat(json(r).get("source").asString()).isEqualTo("PASS");
        ok++;
      } else {
        assertThat(json(r).get("reason").asString()).isEqualTo("daily_cap");
        capped++;
      }
    }
    // The 30-consume cap is per Vietnam day; a run that straddles midnight there may see more.
    assertThat(ok).isBetween(30, 32);
    assertThat(ok + capped).isEqualTo(32);
    assertThat(api.credits(login)).isEqualTo(1);
  }
}
