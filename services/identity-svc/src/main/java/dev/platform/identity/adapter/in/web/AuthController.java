package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.GuestUseCase;
import dev.platform.identity.application.MagicLinkUseCase;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.domain.Email;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  static final String CLIENT_IP_HEADER = "X-Client-Ip";

  private final GuestUseCase guests;
  private final MagicLinkUseCase magicLinks;
  private final SessionUseCase sessions;

  public AuthController(GuestUseCase guests, MagicLinkUseCase magicLinks, SessionUseCase sessions) {
    this.guests = guests;
    this.magicLinks = magicLinks;
    this.sessions = sessions;
  }

  @PostMapping("/guests")
  ResponseEntity<GuestSessionResponse> createGuest() {
    return ResponseEntity.status(HttpStatus.CREATED).body(GuestSessionResponse.of(guests.create()));
  }

  /**
   * Always 202 with the same body: nothing reveals whether the email is known or throttled. {@code
   * X-Client-Ip} is the end user's IP as web saw it (Cf-Connecting-IP behind Cloudflare); it keys
   * the per-client cap and is never stored or logged as such. Missing or not an IP literal: no
   * client cap.
   */
  @PostMapping("/auth/magic-links")
  ResponseEntity<Map<String, String>> requestMagicLink(
      @Valid @RequestBody MagicLinkRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = CLIENT_IP_HEADER, required = false) String clientIp) {
    magicLinks.request(
        new Email(request.email()),
        request.langOrDefault(),
        guestOf(authorization),
        Optional.ofNullable(clientIp));
    return ResponseEntity.accepted().body(Map.of("status", "sent_if_allowed"));
  }

  @PostMapping("/auth/magic-links/verify")
  LoginSessionResponse verify(
      @Valid @RequestBody VerifyRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return LoginSessionResponse.of(magicLinks.verify(request.token(), guestOf(authorization)));
  }

  private Optional<UUID> guestOf(String authorization) {
    return GuestBearer.of(sessions, authorization);
  }
}
