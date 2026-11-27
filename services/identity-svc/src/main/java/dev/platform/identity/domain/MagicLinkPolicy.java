package dev.platform.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Lifetime and rate limits of email magic links: per address (3 per 15 minutes, silent), per client
 * (20 per rolling day, silent), and a global daily cap over every address (a spam relay guard;
 * refused openly, it reveals nothing).
 */
public final class MagicLinkPolicy {

  public static final Duration TTL = Duration.parse("PT15M");
  public static final Duration WINDOW = Duration.parse("PT15M");
  public static final int MAX_REQUESTS = 3;
  public static final Duration DAILY_WINDOW = Duration.ofHours(24);

  /**
   * Links per client (IPv4 address or IPv6 /64, see {@link ClientKey}) per rolling day, silent like
   * the per-address limit: one client cannot spend the whole global cap and lock everyone out of
   * email login. Generous, because many users behind one carrier NAT share an IPv4 address.
   */
  public static final int CLIENT_DAILY_MAX = 20;

  private MagicLinkPolicy() {}

  /** Whether another link may be sent, given the times of the links already sent to the email. */
  public static boolean allows(Collection<Instant> priorRequests, Instant now) {
    Instant start = windowStart(now);
    return priorRequests.stream().filter(t -> t.isAfter(start)).count() < MAX_REQUESTS;
  }

  public static Instant windowStart(Instant now) {
    return now.minus(WINDOW);
  }

  /** Start of the rolling day the global cap counts over. */
  public static Instant dailyWindowStart(Instant now) {
    return now.minus(DAILY_WINDOW);
  }

  /** Whether one more link fits under the global cap, given the links sent in the rolling day. */
  public static boolean underDailyCap(long sentInDailyWindow, int dailyCap) {
    return sentInDailyWindow < dailyCap;
  }

  /** Whether the client may get one more link, given the links it got in the rolling day. */
  public static boolean underClientCap(long sentToClientInDailyWindow) {
    return sentToClientInDailyWindow < CLIENT_DAILY_MAX;
  }

  /** A cap below 1 would refuse every login; it is a configuration error. */
  public static int requireValidDailyCap(int dailyCap) {
    if (dailyCap < 1) {
      throw new IllegalArgumentException("magic link daily cap must be >= 1, got " + dailyCap);
    }
    return dailyCap;
  }

  public static Instant expiresAt(Instant now) {
    return now.plus(TTL);
  }
}
