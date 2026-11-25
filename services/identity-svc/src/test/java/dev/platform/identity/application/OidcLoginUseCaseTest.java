package dev.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.Pkce;
import dev.platform.identity.domain.Subject;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcLoginUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final URI REDIRECT = URI.create("http://localhost:3001/vi/dang-nhap/google");

  InMemoryPorts.Accounts accounts = new InMemoryPorts.Accounts();
  InMemoryPorts.Sessions sessions = new InMemoryPorts.Sessions();
  InMemoryPorts.OidcLogins logins = new InMemoryPorts.OidcLogins();
  InMemoryPorts.FakeOidc oidc = new InMemoryPorts.FakeOidc();
  InMemoryPorts.Merges merges = new InMemoryPorts.Merges();
  InMemoryPorts.Grants grants = new InMemoryPorts.Grants();
  MagicLinkUseCaseTest.MutableClock clock = new MagicLinkUseCaseTest.MutableClock(T0);
  OidcLoginUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase =
        new OidcLoginUseCase(
            accounts,
            sessions,
            logins,
            oidc,
            merges,
            grants,
            clock,
            new SecureRandom(),
            Set.of(REDIRECT),
            org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    oidc.claims = claims("sub-1", "user@example.com", true, null);
  }

  /** Nonce is filled in from the last authorization when null. */
  private IdTokenClaims claims(String sub, String email, boolean verified, String nonce) {
    return new IdTokenClaims(sub, new Email(email), verified, nonce);
  }

  private String begin(Optional<UUID> guest) {
    String state = useCase.start(REDIRECT, guest).state();
    oidc.claims =
        new IdTokenClaims(
            oidc.claims.subject(),
            oidc.claims.email(),
            oidc.claims.emailVerified(),
            oidc.lastNonce);
    return state;
  }

  @Test
  void start_stores_state_nonce_and_verifier_and_sends_the_s256_challenge() {
    UUID guest = UUID.randomUUID();

    AuthorizationStart start = useCase.start(REDIRECT, Optional.of(guest));

    PendingOidcLogin saved = logins.saved.get(start.state());
    assertThat(start.state()).hasSize(43).isEqualTo(oidc.lastState);
    assertThat(saved.nonce()).hasSize(43).isEqualTo(oidc.lastNonce);
    assertThat(saved.codeVerifier()).hasSize(43);
    assertThat(oidc.lastChallenge).isEqualTo(Pkce.challenge(saved.codeVerifier()));
    assertThat(saved.redirectUri()).isEqualTo(REDIRECT);
    assertThat(saved.guestId()).isEqualTo(guest);
    assertThat(saved.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    assertThat(start.authorizationUrl()).isNotNull();
  }

  @Test
  void start_rejects_a_redirect_uri_that_is_not_allow_listed() {
    assertThatThrownBy(() -> useCase.start(URI.create("https://evil.example/cb"), Optional.empty()))
        .isInstanceOf(InvalidRedirectUriException.class);
    assertThat(logins.saved).isEmpty();
  }

  @Test
  void start_when_google_is_not_configured_is_unavailable() {
    oidc.enabled = false;

    assertThatThrownBy(() -> useCase.start(REDIRECT, Optional.empty()))
        .isInstanceOf(OidcUnavailableException.class);
    assertThat(logins.saved).isEmpty();
  }

  @Test
  void callback_when_google_is_not_configured_is_unavailable() {
    oidc.enabled = false;

    assertThatThrownBy(() -> useCase.callback("c", "s", Optional.empty()))
        .isInstanceOf(OidcUnavailableException.class);
  }

  @Test
  void new_user_is_created_with_the_bonus_and_linked_and_gets_a_session() {
    String state = begin(Optional.empty());

    LoginResult result = useCase.callback("the-code", state, Optional.empty());

    assertThat(result.created()).isTrue();
    UUID id = accounts.byEmail.get("user@example.com");
    assertThat(result.session().subject()).isEqualTo(new Subject.Account(id));
    assertThat(result.session().expiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
    assertThat(accounts.byGoogleSub).containsEntry("sub-1", id);
    assertThat(grants.bonuses).containsExactly(id);
    assertThat(oidc.exchangedCodes).containsExactly("the-code");
    assertThat(oidc.exchangedVerifiers).containsExactly(logins.saved.get(state).codeVerifier());
    assertThat(oidc.exchangedRedirects).containsExactly(REDIRECT);
  }

  @Test
  void existing_email_account_is_linked_without_a_second_account_or_bonus() {
    UUID existing = accounts.upsert(new Email("user@example.com"), T0).id();
    String state = begin(Optional.empty());

    LoginResult result = useCase.callback("c", state, Optional.empty());

    assertThat(result.created()).isFalse();
    assertThat(result.session().subject()).isEqualTo(new Subject.Account(existing));
    assertThat(accounts.byEmail).hasSize(1);
    assertThat(accounts.byGoogleSub).containsEntry("sub-1", existing);
    assertThat(grants.bonuses).isEmpty();
  }

  @Test
  void returning_google_user_is_found_by_sub_even_if_the_email_changed() {
    UUID existing = accounts.upsert(new Email("old@example.com"), T0).id();
    accounts.linkGoogleSub(existing, "sub-1");
    oidc.claims = claims("sub-1", "new@example.com", true, null);
    String state = begin(Optional.empty());

    LoginResult result = useCase.callback("c", state, Optional.empty());

    assertThat(result.created()).isFalse();
    assertThat(result.session().subject()).isEqualTo(new Subject.Account(existing));
    assertThat(accounts.byEmail).doesNotContainKey("new@example.com");
    assertThat(grants.bonuses).isEmpty();
  }

  @Test
  void email_account_already_linked_to_another_google_identity_is_not_taken_over() {
    UUID existing = accounts.upsert(new Email("user@example.com"), T0).id();
    accounts.linkGoogleSub(existing, "other-sub");
    String state = begin(Optional.empty());

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(accounts.byGoogleSub).doesNotContainKey("sub-1");
    assertThat(sessions.stored).isEmpty();
  }

  @Test
  void concurrent_first_login_for_the_same_subject_is_retried_once_and_finds_the_winner() {
    UUID winner = accounts.upsert(new Email("user@example.com"), T0).id();
    accounts.conflictOnNextLink = winner;
    String state = begin(Optional.empty());

    LoginResult result = useCase.callback("c", state, Optional.empty());

    assertThat(result.created()).isFalse();
    assertThat(result.session().subject()).isEqualTo(new Subject.Account(winner));
    assertThat(grants.bonuses).isEmpty();
  }

  @Test
  void repeated_conflicts_end_as_unauthenticated() {
    accounts.alwaysConflict = true;
    String state = begin(Optional.empty());

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void guest_from_the_authorization_is_merged_and_its_sessions_revoked() {
    UUID guest = UUID.randomUUID();
    String state = begin(Optional.of(guest));

    LoginResult result = useCase.callback("c", state, Optional.empty());

    assertThat(merges.merged).hasSize(1);
    assertThat(merges.merged.get(0)[0]).isEqualTo(guest);
    assertThat(merges.merged.get(0)[1]).isEqualTo(result.session().subject().id());
  }

  @Test
  void guest_bearer_on_the_callback_takes_precedence() {
    UUID stored = UUID.randomUUID();
    UUID presented = UUID.randomUUID();
    String state = begin(Optional.of(stored));

    useCase.callback("c", state, Optional.of(presented));

    assertThat(merges.merged.get(0)[0]).isEqualTo(presented);
  }

  @Test
  void unknown_state_is_unauthenticated_and_nothing_is_exchanged() {
    assertThatThrownBy(() -> useCase.callback("c", "never-issued", Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(oidc.exchangedCodes).isEmpty();
  }

  @Test
  void state_can_only_be_used_once() {
    String state = begin(Optional.empty());
    useCase.callback("c", state, Optional.empty());

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(oidc.exchangedCodes).hasSize(1);
  }

  @Test
  void state_is_valid_until_ten_minutes_and_not_a_second_longer() {
    String early = begin(Optional.empty());
    clock.advance(Duration.ofMinutes(10).minusSeconds(1));
    assertThat(useCase.callback("c", early, Optional.empty()).session()).isNotNull();

    String late = begin(Optional.empty());
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    assertThatThrownBy(() -> useCase.callback("c", late, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void unverified_email_is_unauthenticated_and_creates_nothing() {
    oidc.claims = claims("sub-1", "user@example.com", false, null);
    String state = begin(Optional.empty());

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(accounts.byEmail).isEmpty();
    assertThat(grants.bonuses).isEmpty();
    assertThat(sessions.stored).isEmpty();
  }

  @Test
  void nonce_mismatch_is_unauthenticated() {
    String state = begin(Optional.empty());
    oidc.claims = claims("sub-1", "user@example.com", true, "someone-elses-nonce");

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(accounts.byEmail).isEmpty();
  }

  @Test
  void missing_nonce_is_unauthenticated() {
    String state = begin(Optional.empty());
    oidc.claims = claims("sub-1", "user@example.com", true, null);

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void provider_rejection_propagates_as_unauthenticated() {
    String state = begin(Optional.empty());
    oidc.failure = new UnauthenticatedException();

    assertThatThrownBy(() -> useCase.callback("c", state, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(accounts.byEmail).isEmpty();
  }
}
