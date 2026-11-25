package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.SessionUseCase;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.Subject;
import java.util.Optional;
import java.util.UUID;

/** The guest behind an optional bearer; a missing or stale bearer is simply no guest. */
final class GuestBearer {

  private GuestBearer() {}

  static Optional<UUID> of(SessionUseCase sessions, String authorization) {
    return Bearer.parse(authorization)
        .flatMap(
            token -> {
              try {
                return sessions.resolve(token) instanceof Subject.Guest g
                    ? Optional.of(g.id())
                    : Optional.<UUID>empty();
              } catch (UnauthenticatedException e) {
                return Optional.empty();
              }
            });
  }
}
