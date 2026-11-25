package dev.platform.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.IssuedSession;
import dev.platform.identity.application.LoginResult;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest({AuthController.class, MeController.class})
@Import({PlatformWebAutoConfiguration.class, IdentityExceptionHandler.class})
class AuthControllerTest {

  static final Instant EXPIRES = Instant.parse("2026-12-25T00:00:00Z");

  @Autowired MockMvc mvc;
  @MockitoBean GuestUseCase guests;
  @MockitoBean MagicLinkUseCase magicLinks;
  @MockitoBean SessionUseCase sessions;

  private ResultActions postJson(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static IssuedSession issued(Subject subject) {
    return new IssuedSession(SessionToken.generate(new SecureRandom()), subject, EXPIRES);
  }

  @Test
  void post_guests_returns_201_with_session_token_and_guest_id() throws Exception {
    UUID id = UUID.randomUUID();
    IssuedSession session = issued(new Subject.Guest(id));
    when(guests.create()).thenReturn(session);

    mvc.perform(post("/guests"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.session_token").value(session.token().value()))
        .andExpect(jsonPath("$.guest_id").value(id.toString()))
        .andExpect(jsonPath("$.expires_at").value("2026-12-25T00:00:00Z"));
  }

  @Test
  void magic_link_request_with_bad_email_is_400_on_field_email() throws Exception {
    postJson("/auth/magic-links", "{\"email\":\"nope\",\"lang\":\"vi\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("email"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void magic_link_request_without_email_is_400_on_field_email() throws Exception {
    postJson("/auth/magic-links", "{\"lang\":\"vi\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("email"));
  }

  @Test
  void magic_link_request_with_bad_lang_is_400_on_field_lang() throws Exception {
    postJson("/auth/magic-links", "{\"email\":\"a@example.com\",\"lang\":\"../x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("lang"));
  }

  @Test
  void magic_link_request_answers_202_with_the_same_body_however_the_use_case_decides()
      throws Exception {
    String first =
        postJson("/auth/magic-links", "{\"email\":\"known@example.com\",\"lang\":\"vi\"}")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("sent_if_allowed"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        postJson("/auth/magic-links", "{\"email\":\"other@example.com\",\"lang\":\"vi\"}")
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(second).isEqualTo(first);
    verify(magicLinks).request(new Email("known@example.com"), "vi", Optional.empty());
  }

  @Test
  void magic_link_request_with_guest_bearer_passes_the_guest_id() throws Exception {
    UUID guestId = UUID.randomUUID();
    when(sessions.resolve("guest-token")).thenReturn(new Subject.Guest(guestId));

    mvc.perform(
            post("/auth/magic-links")
                .header("Authorization", "Bearer guest-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"lang\":\"vi\"}"))
        .andExpect(status().isAccepted());

    verify(magicLinks).request(new Email("a@example.com"), "vi", Optional.of(guestId));
  }

  @Test
  void magic_link_request_with_stale_bearer_still_succeeds_without_a_guest() throws Exception {
    when(sessions.resolve("stale")).thenThrow(new UnauthenticatedException());

    mvc.perform(
            post("/auth/magic-links")
                .header("Authorization", "Bearer stale")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"lang\":\"vi\"}"))
        .andExpect(status().isAccepted());

    verify(magicLinks).request(new Email("a@example.com"), "vi", Optional.empty());
  }

  @Test
  void mail_failure_is_503_upstream_unavailable_without_details() throws Exception {
    org.mockito.Mockito.doThrow(new dev.platform.identity.application.MailDeliveryException())
        .when(magicLinks)
        .request(any(), any(), any());

    postJson("/auth/magic-links", "{\"email\":\"a@example.com\",\"lang\":\"vi\"}")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("urn:problem:upstream-unavailable"))
        .andExpect(jsonPath("$.trace_id").exists())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("example.com"))));
  }

  @Test
  void comma_separated_emails_are_400_on_field_email() throws Exception {
    postJson("/auth/magic-links", "{\"email\":\"a,b@x.com\",\"lang\":\"vi\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("email"));
  }

  @Test
  void verify_is_post_only() throws Exception {
    mvc.perform(get("/auth/magic-links/verify").param("token", "x"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void verify_returns_the_account_session() throws Exception {
    UUID accountId = UUID.randomUUID();
    IssuedSession session = issued(new Subject.Account(accountId));
    when(magicLinks.verify(eq("tok"), any())).thenReturn(new LoginResult(session, true));

    postJson("/auth/magic-links/verify", "{\"token\":\"tok\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session_token").value(session.token().value()))
        .andExpect(jsonPath("$.account_id").value(accountId.toString()))
        .andExpect(jsonPath("$.expires_at").value("2026-12-25T00:00:00Z"))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void verify_with_bad_token_is_401_unauthenticated() throws Exception {
    when(magicLinks.verify(eq("bad"), any())).thenThrow(new UnauthenticatedException());

    postJson("/auth/magic-links/verify", "{\"token\":\"bad\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void verify_without_token_is_400_on_field_token() throws Exception {
    postJson("/auth/magic-links/verify", "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("token"));
  }

  @Test
  void malformed_json_is_400_on_field_body() throws Exception {
    postJson("/auth/magic-links", "{nope")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
  }

  @Test
  void me_without_bearer_is_401_unauthenticated() throws Exception {
    mvc.perform(get("/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void me_with_non_bearer_authorization_is_401() throws Exception {
    mvc.perform(get("/me").header("Authorization", "Basic abc"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void me_for_a_guest() throws Exception {
    UUID id = UUID.randomUUID();
    when(sessions.resolve("t")).thenReturn(new Subject.Guest(id));

    mvc.perform(get("/me").header("Authorization", "Bearer t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("guest"))
        .andExpect(jsonPath("$.guest_id").value(id.toString()))
        .andExpect(jsonPath("$.account_id").doesNotExist())
        .andExpect(jsonPath("$.email_masked").doesNotExist());
  }

  @Test
  void me_for_an_account_masks_the_email() throws Exception {
    UUID id = UUID.randomUUID();
    when(sessions.resolve("t")).thenReturn(new Subject.Account(id));
    when(sessions.accountEmail(id)).thenReturn(Optional.of(new Email("tuan@example.com")));

    mvc.perform(get("/me").header("Authorization", "Bearer t"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("account"))
        .andExpect(jsonPath("$.account_id").value(id.toString()))
        .andExpect(jsonPath("$.email_masked").value("t***@e***.com"))
        .andExpect(jsonPath("$.guest_id").doesNotExist());
  }

  @Test
  void logout_revokes_the_current_session() throws Exception {
    when(sessions.resolve("t")).thenReturn(new Subject.Guest(UUID.randomUUID()));

    mvc.perform(delete("/sessions/current").header("Authorization", "Bearer t"))
        .andExpect(status().isNoContent());

    verify(sessions).revoke("t");
  }

  @Test
  void logout_without_bearer_is_401() throws Exception {
    mvc.perform(delete("/sessions/current")).andExpect(status().isUnauthorized());
  }
}
