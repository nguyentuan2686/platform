package dev.platform.identity.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.identity.application.AuthorizationStart;
import dev.platform.identity.application.InvalidRedirectUriException;
import dev.platform.identity.application.IssuedSession;
import dev.platform.identity.application.LoginResult;
import dev.platform.identity.application.OidcLoginUseCase;
import dev.platform.identity.application.OidcUnavailableException;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.net.URI;
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

@WebMvcTest(GoogleAuthController.class)
@Import({PlatformWebAutoConfiguration.class, IdentityExceptionHandler.class})
class GoogleAuthControllerTest {

  static final URI REDIRECT = URI.create("http://localhost:3001/vi/dang-nhap/google");

  @Autowired MockMvc mvc;
  @MockitoBean OidcLoginUseCase oidc;
  @MockitoBean SessionUseCase sessions;

  private ResultActions callback(String body) throws Exception {
    return mvc.perform(
        post("/auth/google/callback").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void authorization_returns_the_url_and_state() throws Exception {
    when(oidc.start(REDIRECT, Optional.empty()))
        .thenReturn(new AuthorizationStart(URI.create("https://idp.example/a?x=1"), "st"));

    mvc.perform(get("/auth/google/authorization").param("redirect_uri", REDIRECT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorization_url").value("https://idp.example/a?x=1"))
        .andExpect(jsonPath("$.state").value("st"));
  }

  @Test
  void authorization_with_guest_bearer_passes_the_guest() throws Exception {
    UUID guest = UUID.randomUUID();
    when(sessions.resolve("g")).thenReturn(new Subject.Guest(guest));
    when(oidc.start(REDIRECT, Optional.of(guest)))
        .thenReturn(new AuthorizationStart(URI.create("https://idp.example/a"), "st"));

    mvc.perform(
            get("/auth/google/authorization")
                .param("redirect_uri", REDIRECT.toString())
                .header("Authorization", "Bearer g"))
        .andExpect(status().isOk());

    verify(oidc).start(REDIRECT, Optional.of(guest));
  }

  @Test
  void authorization_with_a_redirect_uri_that_is_not_allowed_is_400_on_redirect_uri()
      throws Exception {
    when(oidc.start(any(), any())).thenThrow(new InvalidRedirectUriException());

    mvc.perform(get("/auth/google/authorization").param("redirect_uri", "https://evil.example/"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("redirect_uri"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void authorization_with_an_unparseable_redirect_uri_is_400_on_redirect_uri() throws Exception {
    mvc.perform(get("/auth/google/authorization").param("redirect_uri", "not a uri"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("redirect_uri"));
  }

  @Test
  void authorization_without_redirect_uri_is_400_on_redirect_uri() throws Exception {
    mvc.perform(get("/auth/google/authorization"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("redirect_uri"));
  }

  @Test
  void google_not_configured_is_503_upstream_unavailable() throws Exception {
    when(oidc.start(any(), any())).thenThrow(OidcUnavailableException.notConfigured());

    mvc.perform(get("/auth/google/authorization").param("redirect_uri", REDIRECT.toString()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:upstream-unavailable"))
        .andExpect(jsonPath("$.detail").value("Google login is not configured"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void callback_returns_the_same_body_as_magic_link_verify() throws Exception {
    UUID account = UUID.randomUUID();
    IssuedSession session =
        new IssuedSession(
            SessionToken.generate(new SecureRandom()),
            new Subject.Account(account),
            Instant.parse("2026-12-25T00:00:00Z"));
    when(oidc.callback(eq("c"), eq("s"), any())).thenReturn(new LoginResult(session, true));

    callback("{\"code\":\"c\",\"state\":\"s\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session_token").value(session.token().value()))
        .andExpect(jsonPath("$.account_id").value(account.toString()))
        .andExpect(jsonPath("$.expires_at").value("2026-12-25T00:00:00Z"))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void callback_failure_is_401_unauthenticated() throws Exception {
    when(oidc.callback(any(), any(), any())).thenThrow(new UnauthenticatedException());

    callback("{\"code\":\"c\",\"state\":\"s\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
  }

  @Test
  void callback_without_code_or_state_is_400_on_those_fields() throws Exception {
    callback("{\"state\":\"s\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("code"));
    callback("{\"code\":\"c\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("state"));
  }
}
