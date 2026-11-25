package dev.platform.identity.application;

import dev.platform.identity.domain.Subject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.transaction.annotation.Transactional;

public class GuestUseCase {

  static final Duration SESSION_TTL = Duration.ofDays(180);

  private final SessionRepositoryPort sessions;
  private final Clock clock;
  private final RandomGenerator random;

  public GuestUseCase(SessionRepositoryPort sessions, Clock clock, RandomGenerator random) {
    this.sessions = sessions;
    this.clock = clock;
    this.random = random;
  }

  @Transactional
  public IssuedSession create() {
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    sessions.createGuest(id, now);
    return Sessions.issue(sessions, random, new Subject.Guest(id), SESSION_TTL, now);
  }
}
