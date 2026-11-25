package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public class SessionUseCase {

  private final SessionRepositoryPort sessions;
  private final AccountRepositoryPort accounts;
  private final Clock clock;

  public SessionUseCase(
      SessionRepositoryPort sessions, AccountRepositoryPort accounts, Clock clock) {
    this.sessions = sessions;
    this.accounts = accounts;
    this.clock = clock;
  }

  /** The subject behind a bearer token; throws when it is missing, unknown, expired or revoked. */
  public Subject resolve(String bearer) {
    SessionToken token = SessionToken.parse(bearer).orElseThrow(UnauthenticatedException::new);
    return sessions
        .findActive(token.sha256(), clock.instant())
        .orElseThrow(UnauthenticatedException::new);
  }

  public void revoke(String bearer) {
    SessionToken.parse(bearer).ifPresent(t -> sessions.revoke(t.sha256(), clock.instant()));
  }

  public Optional<Email> accountEmail(UUID accountId) {
    return accounts.findEmail(accountId);
  }
}
