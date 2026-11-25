package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.AuthorizationStart;
import dev.platform.identity.application.InvalidRedirectUriException;
import dev.platform.identity.application.OidcLoginUseCase;
import dev.platform.identity.application.SessionUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoogleAuthController {

  private final OidcLoginUseCase google;
  private final SessionUseCase sessions;

  public GoogleAuthController(OidcLoginUseCase google, SessionUseCase sessions) {
    this.google = google;
    this.sessions = sessions;
  }

  @GetMapping("/auth/google/authorization")
  AuthorizationResponse authorization(
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    AuthorizationStart start =
        google.start(parseRedirect(redirectUri), GuestBearer.of(sessions, authorization));
    return new AuthorizationResponse(start.authorizationUrl().toString(), start.state());
  }

  @PostMapping("/auth/google/callback")
  LoginSessionResponse callback(
      @Valid @RequestBody GoogleCallbackRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return LoginSessionResponse.of(
        google.callback(request.code(), request.state(), GuestBearer.of(sessions, authorization)));
  }

  private static URI parseRedirect(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidRedirectUriException();
    }
    try {
      return new URI(raw);
    } catch (URISyntaxException e) {
      throw new InvalidRedirectUriException();
    }
  }

  public record AuthorizationResponse(
      @JsonProperty("authorization_url") String authorizationUrl, String state) {

    @Override
    public String toString() {
      return "AuthorizationResponse[redacted]";
    }
  }
}
