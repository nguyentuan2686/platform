package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.ProfileNotFoundException;
import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.ShareUseCase;
import dev.platform.identity.domain.Subject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The public share link of the caller's chart profile, and its service-to-service lookup. */
@RestController
public class ShareController {

  private final ShareUseCase shares;
  private final SessionUseCase sessions;

  public ShareController(ShareUseCase shares, SessionUseCase sessions) {
    this.shares = shares;
    this.sessions = sessions;
  }

  @PutMapping("/me/profile/share")
  ShareResponse create(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return ShareResponse.of(shares.create(subject(authorization)));
  }

  @GetMapping("/me/profile/share")
  ShareResponse get(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return shares
        .get(subject(authorization))
        .map(ShareResponse::of)
        .orElseThrow(ProfileNotFoundException::new);
  }

  @DeleteMapping("/me/profile/share")
  ResponseEntity<Void> revoke(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    shares.revoke(subject(authorization));
    return ResponseEntity.noContent().build();
  }

  /** Internal token only: the caller is a service, not a session. */
  @GetMapping("/shares/{share_id}")
  SharedProfileResponse resolve(@PathVariable("share_id") String shareId) {
    return shares
        .resolve(shareId)
        .map(SharedProfileResponse::of)
        .orElseThrow(ProfileNotFoundException::new);
  }

  private Subject subject(String authorization) {
    return sessions.resolve(Bearer.require(authorization));
  }
}
