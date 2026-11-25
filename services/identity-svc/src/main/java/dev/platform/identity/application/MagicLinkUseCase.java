package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.MagicLinkPolicy;
import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.transaction.annotation.Transactional;

public class MagicLinkUseCase {

  static final Duration ACCOUNT_SESSION_TTL = Duration.ofDays(30);

  private final AccountRepositoryPort accounts;
  private final SessionRepositoryPort sessions;
  private final MagicLinkRepositoryPort links;
  private final MailerPort mailer;
  private final AccountMergePort merges;
  private final EntitlementGrantPort grants;
  private final Clock clock;
  private final RandomGenerator random;
  private final String webBaseUrl;

  public MagicLinkUseCase(
      AccountRepositoryPort accounts,
      SessionRepositoryPort sessions,
      MagicLinkRepositoryPort links,
      MailerPort mailer,
      AccountMergePort merges,
      EntitlementGrantPort grants,
      Clock clock,
      RandomGenerator random,
      URI webBaseUrl) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.links = links;
    this.mailer = mailer;
    this.merges = merges;
    this.grants = grants;
    this.clock = clock;
    this.random = random;
    String base = webBaseUrl.toString();
    this.webBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /**
   * Sends a login link. Silently does nothing when the email is rate limited, so callers cannot
   * tell the difference (nor whether the email has an account).
   */
  @Transactional
  public void request(Email email, String lang, Optional<UUID> guestId) {
    Instant now = clock.instant();
    if (!MagicLinkPolicy.allows(
        links.requestTimesSince(email, MagicLinkPolicy.windowStart(now)), now)) {
      return;
    }
    SessionToken token = SessionToken.generate(random);
    links.save(
        new PendingMagicLink(
            UUID.randomUUID(),
            email,
            token.sha256(),
            guestId.orElse(null),
            now,
            MagicLinkPolicy.expiresAt(now)));
    URI link = URI.create(webBaseUrl + "/" + lang + "/dang-nhap/xac-nhan?token=" + token.value());
    mailer.sendMagicLink(email, link, lang);
  }

  /** Redeems a link: finds or creates the account, merges the guest, opens a 30-day session. */
  @Transactional
  public LoginResult verify(String rawToken, Optional<UUID> guestId) {
    Instant now = clock.instant();
    SessionToken token = SessionToken.parse(rawToken).orElseThrow(UnauthenticatedException::new);
    ConsumedMagicLink consumed =
        links.consume(token.sha256(), now).orElseThrow(UnauthenticatedException::new);
    AccountRepositoryPort.Upserted account = accounts.upsert(consumed.email(), now);
    if (account.created()) {
      grants.signupBonus(account.id());
    }
    Optional<UUID> guest = guestId.or(() -> Optional.ofNullable(consumed.guestId()));
    guest.ifPresent(
        g -> {
          merges.mergeGuest(g, account.id());
          sessions.revokeGuestSessions(g, now);
        });
    IssuedSession session =
        Sessions.issue(
            sessions, random, new Subject.Account(account.id()), ACCOUNT_SESSION_TTL, now);
    return new LoginResult(session, account.created());
  }
}
