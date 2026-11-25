package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.Subject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final SessionUseCase sessions;

  public MeController(SessionUseCase sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/me")
  MeResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
    Subject subject = sessions.resolve(Bearer.require(authorization));
    return switch (subject) {
      case Subject.Guest g -> MeResponse.guest(g.id());
      case Subject.Account a ->
          MeResponse.account(a.id(), sessions.accountEmail(a.id()).map(Email::masked).orElse(null));
    };
  }

  @DeleteMapping("/sessions/current")
  ResponseEntity<Void> logout(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    String token = Bearer.require(authorization);
    sessions.resolve(token);
    sessions.revoke(token);
    return ResponseEntity.noContent().build();
  }
}
