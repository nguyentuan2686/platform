package dev.platform.reading.tuvi.adapter.out.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.qa.ConsumeOutcome;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.application.qa.RefundOutcome;
import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IdentitySvcClientTest {

  static final String BASE = "http://identity.test";
  static final String TOKEN = "local-test-internal-token";
  static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID READING = UUID.fromString("22222222-2222-2222-2222-222222222222");

  MockRestServiceServer server;
  IdentitySvcClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new IdentitySvcClient(builder, BASE, TOKEN);
  }

  @Test
  void who_am_i_forwards_bearer_and_internal_token() {
    server
        .expect(requestTo(BASE + "/me"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer sess-1"))
        .andExpect(header("X-Internal-Token", TOKEN))
        .andRespond(
            withSuccess(
                "{\"kind\":\"account\",\"account_id\":\"" + ACCOUNT + "\"}",
                MediaType.APPLICATION_JSON));

    assertThat(client.whoAmI("sess-1")).isEqualTo(new IdentitySubject("account", ACCOUNT));
    server.verify();
  }

  @Test
  void who_am_i_guest() {
    server
        .expect(requestTo(BASE + "/me"))
        .andRespond(
            withSuccess(
                "{\"kind\":\"guest\",\"guest_id\":\"" + ACCOUNT + "\"}",
                MediaType.APPLICATION_JSON));

    IdentitySubject s = client.whoAmI("g");

    assertThat(s.guest()).isTrue();
    assertThat(s.id()).isEqualTo(ACCOUNT);
  }

  @Test
  void who_am_i_401_is_unauthenticated() {
    server.expect(requestTo(BASE + "/me")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> client.whoAmI("bad")).isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void profile_maps_birth_and_calendar() {
    server
        .expect(requestTo(BASE + "/me/profile"))
        .andExpect(header("Authorization", "Bearer sess-1"))
        .andExpect(header("X-Internal-Token", TOKEN))
        .andRespond(
            withSuccess(
                "{\"id\":\""
                    + ACCOUNT
                    + "\",\"label\":\"Nguyễn Văn A\",\"birth\":{\"birth\":"
                    + "\"1994-03-12T08:30\",\"zone\":\"Asia/Ho_Chi_Minh\",\"gender\":\"male\"},"
                    + "\"calendar\":\"CN\",\"locked\":false}",
                MediaType.APPLICATION_JSON));

    Optional<Birth> birth = client.profile("sess-1");

    assertThat(birth).isPresent();
    Birth b = birth.get();
    assertThat(List.of(b.birth(), b.zone(), b.gender(), b.calendar()))
        .containsExactly("1994-03-12T08:30", "Asia/Ho_Chi_Minh", "male", "CN");
  }

  @Test
  void profile_404_is_empty() {
    server.expect(requestTo(BASE + "/me/profile")).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.profile("sess-1")).isEmpty();
  }

  @Test
  void consume_charges() {
    server
        .expect(requestTo(BASE + "/internal/credits/consume"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Token", TOKEN))
        .andExpect(headerDoesNotExist("Authorization"))
        .andExpect(
            content()
                .json(
                    "{\"account_id\":\""
                        + ACCOUNT
                        + "\",\"reading_id\":\""
                        + READING
                        + "\",\"topic\":\"TaiLoc\"}"))
        .andRespond(
            withSuccess("{\"source\":\"PASS\",\"credits_left\":0}", MediaType.APPLICATION_JSON));

    assertThat(client.consume(ACCOUNT, READING, QaTopic.TaiLoc))
        .isEqualTo(new ConsumeOutcome.Charged("PASS"));
    server.verify();
  }

  @Test
  void each_409_reason_is_refused_verbatim() {
    List<String> reasons =
        List.of("no_credits", "daily_cap", "no_profile", "reading_conflict", "reading_refunded");
    for (String reason : reasons) {
      server
          .expect(requestTo(BASE + "/internal/credits/consume"))
          .andRespond(
              withStatus(HttpStatus.CONFLICT)
                  .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                  .body(
                      "{\"type\":\"urn:problem:conflict\",\"status\":409,\"reason\":\""
                          + reason
                          + "\"}"));
    }

    for (String reason : reasons) {
      assertThat(client.consume(ACCOUNT, READING, QaTopic.TaiLoc))
          .isEqualTo(new ConsumeOutcome.Refused(reason));
    }
  }

  @Test
  void consume_500_or_timeout_is_identity_unavailable() {
    server
        .expect(requestTo(BASE + "/internal/credits/consume"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    server
        .expect(requestTo(BASE + "/internal/credits/consume"))
        .andRespond(withException(new SocketTimeoutException("read timed out")));

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> client.consume(ACCOUNT, READING, QaTopic.TaiLoc))
          .isInstanceOf(UpstreamUnavailableException.class)
          .satisfies(
              t -> assertThat(((UpstreamUnavailableException) t).upstream()).isEqualTo("identity"));
    }
  }

  @Test
  void consume_409_without_reason_is_unavailable() {
    server
        .expect(requestTo(BASE + "/internal/credits/consume"))
        .andRespond(withStatus(HttpStatus.CONFLICT).body("{}"));

    assertThatThrownBy(() -> client.consume(ACCOUNT, READING, QaTopic.TaiLoc))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void refund_200_and_404() {
    server
        .expect(requestTo(BASE + "/internal/credits/refund"))
        .andExpect(header("X-Internal-Token", TOKEN))
        .andExpect(content().json("{\"reading_id\":\"" + READING + "\"}"))
        .andRespond(withSuccess("{\"refunded\":true}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(BASE + "/internal/credits/refund"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.refund(READING)).isEqualTo(RefundOutcome.REFUNDED);
    assertThat(client.refund(READING)).isEqualTo(RefundOutcome.NOT_CHARGED);
  }

  @Test
  void refund_and_me_errors_are_identity_unavailable() {
    server
        .expect(requestTo(BASE + "/internal/credits/refund"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    server.expect(requestTo(BASE + "/me")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo(BASE + "/me/profile"))
        .andRespond(withException(new SocketTimeoutException("t")));
    server
        .expect(requestTo(BASE + "/me"))
        .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.refund(READING))
        .isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> client.whoAmI("b")).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> client.profile("b")).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(() -> client.whoAmI("b")).isInstanceOf(UpstreamUnavailableException.class);
  }
}
