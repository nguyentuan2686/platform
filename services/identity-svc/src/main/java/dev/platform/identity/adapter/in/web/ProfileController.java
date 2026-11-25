package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.ProfileNotFoundException;
import dev.platform.identity.application.ProfileUseCase;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Subject;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The caller's single chart profile (guest or account bearer). */
@RestController
public class ProfileController {

  private final ProfileUseCase profiles;
  private final SessionUseCase sessions;

  public ProfileController(ProfileUseCase profiles, SessionUseCase sessions) {
    this.profiles = profiles;
    this.sessions = sessions;
  }

  @GetMapping("/me/profile")
  ProfileResponse get(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return profiles
        .get(subject(authorization))
        .map(ProfileResponse::of)
        .orElseThrow(ProfileNotFoundException::new);
  }

  @PutMapping("/me/profile")
  ProfileResponse put(
      @Valid @RequestBody ProfileRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Subject subject = subject(authorization);
    BirthData birth =
        new BirthData(request.birth().birth(), request.birth().zone(), request.birth().gender());
    return ProfileResponse.of(profiles.put(subject, request.label(), birth, request.calendar()));
  }

  @DeleteMapping("/me/profile")
  ResponseEntity<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    profiles.delete(subject(authorization));
    return ResponseEntity.noContent().build();
  }

  private Subject subject(String authorization) {
    return sessions.resolve(Bearer.require(authorization));
  }
}
