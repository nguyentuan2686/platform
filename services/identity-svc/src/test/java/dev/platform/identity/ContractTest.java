package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.platform.identity.adapter.in.web.AuthController;
import dev.platform.identity.adapter.in.web.GoogleAuthController;
import dev.platform.identity.adapter.in.web.IdentityExceptionHandler;
import dev.platform.identity.adapter.in.web.MeController;
import dev.platform.identity.adapter.in.web.ProfileController;
import dev.platform.identity.application.AuthorizationStart;
import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.InvalidRedirectUriException;
import dev.platform.identity.application.IssuedSession;
import dev.platform.identity.application.LoginResult;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.OidcLoginUseCase;
import dev.platform.identity.application.OidcUnavailableException;
import dev.platform.identity.application.ProfileUseCase;
import dev.platform.identity.application.ProfileView;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.ChartProfile;
import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.ProfileLockedException;
import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({
  AuthController.class,
  MeController.class,
  GoogleAuthController.class,
  ProfileController.class
})
@Import({PlatformWebAutoConfiguration.class, IdentityExceptionHandler.class})
class ContractTest {

  static final Instant EXPIRES = Instant.parse("2026-12-25T00:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerMapping mappings;
  @MockitoBean GuestUseCase guests;
  @MockitoBean MagicLinkUseCase magicLinks;
  @MockitoBean SessionUseCase sessions;
  @MockitoBean OidcLoginUseCase oidc;
  @MockitoBean ProfileUseCase profiles;

  private static IssuedSession issued(Subject subject) {
    return new IssuedSession(SessionToken.generate(new SecureRandom()), subject, EXPIRES);
  }

  @Test
  void every_path_and_method_in_the_spec_is_served() {
    Set<String> served = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
      for (RequestMethod m : e.getKey().getMethodsCondition().getMethods()) {
        e.getKey().getPatternValues().forEach(p -> served.add(m + " " + p));
      }
    }
    Set<String> declared = new HashSet<>();
    JsonNode paths = ContractSupport.openApi().get("paths");
    for (String path : paths.propertyNames()) {
      if (path.startsWith("/health/")) {
        continue; // actuator group, not a controller mapping
      }
      for (String method : paths.get(path).propertyNames()) {
        declared.add(method.toUpperCase(Locale.ROOT) + " " + path);
      }
    }

    assertThat(declared)
        .containsExactlyInAnyOrder(
            "POST /guests",
            "POST /auth/magic-links",
            "POST /auth/magic-links/verify",
            "GET /auth/google/authorization",
            "POST /auth/google/callback",
            "GET /me",
            "GET /me/profile",
            "PUT /me/profile",
            "DELETE /me/profile",
            "DELETE /sessions/current");
    assertThat(served).containsAll(declared);
  }

  @Test
  void guest_response_matches_the_spec() throws Exception {
    when(guests.create()).thenReturn(issued(new Subject.Guest(UUID.randomUUID())));

    JsonNode json = body(mvc.perform(post("/guests")), 201);

    assertThat(ContractSupport.componentSchema("GuestSession").validate(json)).isEmpty();
  }

  @Test
  void magic_link_accepted_matches_the_spec() throws Exception {
    JsonNode json = body(mvc.perform(json("/auth/magic-links", "{\"email\":\"a@b.co\"}")), 202);

    assertThat(ContractSupport.componentSchema("MagicLinkAccepted").validate(json)).isEmpty();
  }

  @Test
  void login_response_matches_the_spec() throws Exception {
    when(magicLinks.verify(eq("tok"), any()))
        .thenReturn(new LoginResult(issued(new Subject.Account(UUID.randomUUID())), false));

    JsonNode json = body(mvc.perform(json("/auth/magic-links/verify", "{\"token\":\"tok\"}")), 200);

    assertThat(ContractSupport.componentSchema("LoginSession").validate(json)).isEmpty();
  }

  @Test
  void google_responses_match_the_spec() throws Exception {
    URI redirect = URI.create("http://localhost:3001/vi/dang-nhap/google");
    when(oidc.start(eq(redirect), any()))
        .thenReturn(
            new AuthorizationStart(
                URI.create("https://accounts.google.com/o/oauth2/v2/auth?x=1"),
                SessionToken.generate(new SecureRandom()).value()));
    when(oidc.callback(eq("c"), eq("s"), any()))
        .thenReturn(new LoginResult(issued(new Subject.Account(UUID.randomUUID())), true));

    JsonNode authorization =
        body(
            mvc.perform(
                get("/auth/google/authorization").param("redirect_uri", redirect.toString())),
            200);
    JsonNode login =
        body(mvc.perform(json("/auth/google/callback", "{\"code\":\"c\",\"state\":\"s\"}")), 200);

    assertThat(ContractSupport.componentSchema("GoogleAuthorization").validate(authorization))
        .isEmpty();
    assertThat(ContractSupport.componentSchema("LoginSession").validate(login)).isEmpty();
  }

  @Test
  void google_problems_match_the_spec() throws Exception {
    org.mockito.Mockito.doThrow(new InvalidRedirectUriException()).when(oidc).start(any(), any());
    JsonNode badRedirect =
        body(
            mvc.perform(get("/auth/google/authorization").param("redirect_uri", "https://x.y/")),
            400);
    when(oidc.callback(any(), any(), any())).thenThrow(new UnauthenticatedException());
    JsonNode rejected =
        body(mvc.perform(json("/auth/google/callback", "{\"code\":\"c\",\"state\":\"s\"}")), 401);
    org.mockito.Mockito.doThrow(OidcUnavailableException.notConfigured())
        .when(oidc)
        .start(any(), any());
    JsonNode disabled =
        body(
            mvc.perform(get("/auth/google/authorization").param("redirect_uri", "https://x.y/")),
            503);

    for (JsonNode problem : new JsonNode[] {badRedirect, rejected, disabled}) {
      assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    }
    assertThat(badRedirect.get("errors").get(0).get("field").asString()).isEqualTo("redirect_uri");
    assertThat(disabled.get("detail").asString()).isEqualTo("Google login is not configured");
  }

  @Test
  void me_responses_match_the_spec() throws Exception {
    UUID account = UUID.randomUUID();
    when(sessions.resolve("g")).thenReturn(new Subject.Guest(UUID.randomUUID()));
    when(sessions.resolve("a")).thenReturn(new Subject.Account(account));
    when(sessions.accountEmail(account)).thenReturn(Optional.of(new Email("tuan@gmail.com")));

    JsonNode guest = body(mvc.perform(get("/me").header("Authorization", "Bearer g")), 200);
    JsonNode acct = body(mvc.perform(get("/me").header("Authorization", "Bearer a")), 200);

    assertThat(ContractSupport.componentSchema("Me").validate(guest)).isEmpty();
    assertThat(ContractSupport.componentSchema("Me").validate(acct)).isEmpty();
  }

  @Test
  void logout_is_204() throws Exception {
    when(sessions.resolve("t")).thenReturn(new Subject.Guest(UUID.randomUUID()));

    assertThat(
            mvc.perform(delete("/sessions/current").header("Authorization", "Bearer t"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void problems_match_the_spec() throws Exception {
    JsonNode unauthenticated = body(mvc.perform(get("/me")), 401);
    JsonNode invalid = body(mvc.perform(json("/auth/magic-links", "{\"email\":\"x\"}")), 400);
    when(magicLinks.verify(eq("bad"), any())).thenThrow(new UnauthenticatedException());
    JsonNode badToken =
        body(mvc.perform(json("/auth/magic-links/verify", "{\"token\":\"bad\"}")), 401);

    for (JsonNode problem : new JsonNode[] {unauthenticated, invalid, badToken}) {
      assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    }
    assertThat(unauthenticated.get("type").asString()).isEqualTo("urn:problem:unauthenticated");
  }

  private static final String PUT_BODY =
      "{\"label\":\"Me\",\"birth\":{\"birth\":\"1994-03-12T05:30\",\"gender\":\"female\"},"
          + "\"calendar\":\"VN\"}";

  private static ProfileView profileView(boolean locked) {
    return new ProfileView(
        ChartProfile.create(
            UUID.randomUUID(),
            "Me",
            new BirthData("1994-03-12T05:30", null, "female"),
            Calendar.VN),
        locked);
  }

  @Test
  void profile_responses_match_the_spec() throws Exception {
    when(sessions.resolve("a")).thenReturn(new Subject.Account(UUID.randomUUID()));
    when(profiles.get(any())).thenReturn(Optional.of(profileView(true)));
    when(profiles.put(any(), eq("Me"), any(), eq(Calendar.VN))).thenReturn(profileView(false));

    JsonNode got = body(mvc.perform(get("/me/profile").header("Authorization", "Bearer a")), 200);
    JsonNode put =
        body(
            mvc.perform(
                put("/me/profile")
                    .header("Authorization", "Bearer a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PUT_BODY)),
            200);

    assertThat(ContractSupport.componentSchema("Profile").validate(got)).isEmpty();
    assertThat(ContractSupport.componentSchema("Profile").validate(put)).isEmpty();
    assertThat(got.get("birth").get("zone").asString()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(
            ContractSupport.componentSchema("ProfileRequest")
                .validate(JsonMapper.shared().readTree(PUT_BODY)))
        .isEmpty();
  }

  @Test
  void profile_problems_match_the_spec_and_never_echo_birth_data() throws Exception {
    when(sessions.resolve("a")).thenReturn(new Subject.Account(UUID.randomUUID()));
    when(profiles.get(any())).thenReturn(Optional.empty());
    JsonNode missing =
        body(mvc.perform(get("/me/profile").header("Authorization", "Bearer a")), 404);
    when(profiles.put(any(), any(), any(), any())).thenThrow(new ProfileLockedException());
    JsonNode locked =
        body(
            mvc.perform(
                put("/me/profile")
                    .header("Authorization", "Bearer a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PUT_BODY)),
            409);
    JsonNode invalid =
        body(
            mvc.perform(
                put("/me/profile")
                    .header("Authorization", "Bearer a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PUT_BODY.replace("1994-03-12T05:30", "1994-02-30T05:30"))),
            400);
    JsonNode noAuth = body(mvc.perform(get("/me/profile")), 401);

    for (JsonNode problem : new JsonNode[] {missing, locked, invalid, noAuth}) {
      assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    }
    assertThat(locked.get("reason").asString()).isEqualTo("profile_locked");
    assertThat(locked.get("type").asString()).isEqualTo("urn:problem:conflict");
    assertThat(invalid.get("errors").get(0).get("field").asString()).isEqualTo("birth.birth");
    assertThat(invalid.toString()).doesNotContain("1994");
  }

  @Test
  void delete_profile_is_204() throws Exception {
    when(sessions.resolve("a")).thenReturn(new Subject.Account(UUID.randomUUID()));

    assertThat(
            mvc.perform(delete("/me/profile").header("Authorization", "Bearer a"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
  }

  @Test
  void schema_checks_are_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"kind\":\"robot\"}");
    assertThat(ContractSupport.componentSchema("Me").validate(bad)).isNotEmpty();
    assertThat(ContractSupport.componentSchema("GuestSession").validate(bad)).isNotEmpty();
    assertThat(ContractSupport.componentSchema("Problem").validate(bad)).isNotEmpty();
  }

  private static MockHttpServletRequestBuilder json(String path, String body) {
    return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static JsonNode body(ResultActions result, int status) throws Exception {
    var response = result.andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    return JsonMapper.shared().readTree(response.getContentAsString());
  }
}
