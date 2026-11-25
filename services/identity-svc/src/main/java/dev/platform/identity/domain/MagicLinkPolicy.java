package dev.platform.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/** Lifetime and rate limit of email magic links. */
public final class MagicLinkPolicy {

  public static final Duration TTL = Duration.parse("PT15M");
  public static final Duration WINDOW = Duration.parse("PT15M");
  public static final int MAX_REQUESTS = 3;

  private MagicLinkPolicy() {}

  /** Whether another link may be sent, given the times of the links already sent to the email. */
  public static boolean allows(Collection<Instant> priorRequests, Instant now) {
    Instant start = windowStart(now);
    return priorRequests.stream().filter(t -> t.isAfter(start)).count() < MAX_REQUESTS;
  }

  public static Instant windowStart(Instant now) {
    return now.minus(WINDOW);
  }

  public static Instant expiresAt(Instant now) {
    return now.plus(TTL);
  }
}
