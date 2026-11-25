package dev.platform.identity.application;

import dev.platform.identity.domain.Pkce;
import dev.platform.identity.domain.Subject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.transaction.support.TransactionOperations;

/** Google login: authorization code with PKCE, reusing the magic-link account and session path. */
public class OidcLoginUseCase {

  static final Duration STATE_TTL = Duration.ofMinutes(10);

  private final AccountRepositoryPort accounts;
  private final SessionRepositoryPort sessions;
  private final OidcLoginRepositoryPort logins;
  private final OidcPort oidc;
  private final AccountMergePort merges;
  private final EntitlementGrantPort grants;
  private final Clock clock;
  private final RandomGenerator random;
  private final Set<URI> allowedRedirects;
  private final TransactionOperations tx;

  public OidcLoginUseCase(
      AccountRepositoryPort accounts,
      SessionRepositoryPort sessions,
      OidcLoginRepositoryPort logins,
      OidcPort oidc,
      AccountMergePort merges,
      EntitlementGrantPort grants,
      Clock clock,
      RandomGenerator random,
      Set<URI> allowedRedirects,
      TransactionOperations tx) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.logins = logins;
    this.oidc = oidc;
    this.merges = merges;
    this.grants = grants;
    this.clock = clock;
    this.random = random;
    this.allowedRedirects = Set.copyOf(allowedRedirects);
    this.tx = tx;
  }

  /**
   * Records state, nonce and PKCE verifier and returns where to send the browser. The provider is
   * contacted (discovery) before, and the state saved in a short transaction after, never inside
   * one.
   */
  public AuthorizationStart start(URI redirectUri, Optional<UUID> guestId) {
    requireEnabled();
    if (redirectUri == null || !allowedRedirects.contains(redirectUri)) {
      throw new InvalidRedirectUriException();
    }
    Instant now = clock.instant();
    String state = Pkce.randomValue(random);
    String nonce = Pkce.randomValue(random);
    String verifier = Pkce.randomValue(random);
    URI url = oidc.authorizationUri(state, nonce, Pkce.challenge(verifier), redirectUri);
    PendingOidcLogin pending =
        new PendingOidcLogin(
            state, nonce, verifier, redirectUri, guestId.orElse(null), now.plus(STATE_TTL));
    tx.executeWithoutResult(status -> logins.save(pending));
    return new AuthorizationStart(url, state);
  }

  /**
   * Completes the login in three steps, none holding a transaction across the provider call: (1)
   * consume the state in its own committed transaction, so it is burned whatever happens next; (2)
   * exchange the code and validate the ID token with no transaction; (3) find, create or link the
   * account and issue the session in one short transaction.
   */
  public LoginResult callback(String code, String state, Optional<UUID> guestId) {
    requireEnabled();
    Instant now = clock.instant();
    PendingOidcLogin login =
        tx.execute(status -> logins.consume(state, now)).orElseThrow(UnauthenticatedException::new);
    IdTokenClaims claims = oidc.exchange(code, login.codeVerifier(), login.redirectUri());
    if (!sameSecret(login.nonce(), claims.nonce()) || !claims.emailVerified()) {
      throw new UnauthenticatedException();
    }
    try {
      return tx.execute(status -> signIn(claims, login, guestId, now));
    } catch (AccountConflictException first) {
      // A concurrent first login for the same Google subject won; look it up again, once.
      try {
        return tx.execute(status -> signIn(claims, login, guestId, now));
      } catch (AccountConflictException second) {
        throw new UnauthenticatedException();
      }
    }
  }

  private LoginResult signIn(
      IdTokenClaims claims, PendingOidcLogin login, Optional<UUID> guestId, Instant now) {
    UUID accountId;
    boolean created = false;
    Optional<UUID> bySub = accounts.findByGoogleSub(claims.subject());
    if (bySub.isPresent()) {
      accountId = bySub.get();
    } else {
      AccountRepositoryPort.Upserted upserted = accounts.upsert(claims.email(), now);
      if (!accounts.linkGoogleSub(upserted.id(), claims.subject())) {
        // The email's account already belongs to another Google identity: never take it over.
        throw new UnauthenticatedException();
      }
      accountId = upserted.id();
      created = upserted.created();
    }
    if (created) {
      grants.signupBonus(accountId);
    }
    Optional<UUID> guest = guestId.or(() -> Optional.ofNullable(login.guestId()));
    guest.ifPresent(
        g -> {
          merges.mergeGuest(g, accountId);
          sessions.revokeGuestSessions(g, now);
        });
    IssuedSession session =
        Sessions.issue(
            sessions,
            random,
            new Subject.Account(accountId),
            MagicLinkUseCase.ACCOUNT_SESSION_TTL,
            now);
    return new LoginResult(session, created);
  }

  private void requireEnabled() {
    if (!oidc.enabled()) {
      throw OidcUnavailableException.notConfigured();
    }
  }

  private static boolean sameSecret(String expected, String presented) {
    return presented != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }
}
