package dev.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.domain.ClientAddress;
import dev.platform.identity.domain.ClientKey;
import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.MagicLinkPolicy;
import dev.platform.identity.domain.Subject;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MagicLinkUseCaseTest {

  static final Instant T0 = Instant.parse("2026-11-25T15:00:00Z");
  // Test keyer: distinct 64-hex keys for the short addresses used here (hex of the bytes, padded).
  static final ClientKeyerPort KEYER =
      a ->
          new ClientKey(
              String.format("%064x", new java.math.BigInteger(1, a.getBytes())).substring(0, 64));

  InMemoryPorts.Accounts accounts = new InMemoryPorts.Accounts();
  InMemoryPorts.Sessions sessions = new InMemoryPorts.Sessions();
  InMemoryPorts.MagicLinks links = new InMemoryPorts.MagicLinks();
  InMemoryPorts.Mailer mailer = new InMemoryPorts.Mailer();
  InMemoryPorts.Merges merges = new InMemoryPorts.Merges();
  InMemoryPorts.Grants grants = new InMemoryPorts.Grants();
  MutableClock clock = new MutableClock(T0);
  MagicLinkUseCase useCase;
  SessionUseCase sessionUseCase;
  GuestUseCase guests;

  @BeforeEach
  void setUp() {
    SecureRandom random = new SecureRandom();
    useCase = useCase("http://localhost:3001");
    sessionUseCase = new SessionUseCase(sessions, accounts, clock);
    guests = new GuestUseCase(sessions, clock, random);
  }

  private MagicLinkUseCase useCase(String webBaseUrl) {
    return useCase(webBaseUrl, 200);
  }

  private MagicLinkUseCase useCase(String webBaseUrl, int dailyCap) {
    return new MagicLinkUseCase(
        accounts,
        sessions,
        links,
        mailer,
        merges,
        grants,
        clock,
        new SecureRandom(),
        URI.create(webBaseUrl),
        dailyCap,
        KEYER);
  }

  @Test
  void known_and_unknown_email_each_get_exactly_one_mail() {
    accounts.upsert(new Email("known@example.com"), T0);

    useCase.request(new Email("known@example.com"), "vi", Optional.empty());
    useCase.request(new Email("unknown@example.com"), "vi", Optional.empty());

    assertThat(mailer.sent).hasSize(2);
    assertThat(mailer.sent.get(0).to().value()).isEqualTo("known@example.com");
    assertThat(mailer.sent.get(1).to().value()).isEqualTo("unknown@example.com");
  }

  @Test
  void link_points_at_the_web_confirmation_page() {
    useCase.request(new Email("a@example.com"), "vi", Optional.empty());

    assertThat(mailer.sent.get(0).link().toString())
        .startsWith("http://localhost:3001/vi/dang-nhap/xac-nhan?token=");
    assertThat(mailer.sent.get(0).lang()).isEqualTo("vi");
  }

  @Test
  void trailing_slash_on_base_url_does_not_double_up() {
    useCase("http://localhost:3001/").request(new Email("a@example.com"), "vi", Optional.empty());

    assertThat(mailer.sent.get(0).link().toString())
        .startsWith("http://localhost:3001/vi/dang-nhap/xac-nhan?token=");
  }

  @Test
  void fourth_request_within_the_window_sends_nothing_and_returns_normally() {
    Email email = new Email("a@example.com");
    for (int i = 0; i < 3; i++) {
      useCase.request(email, "vi", Optional.empty());
      clock.advance(Duration.ofMinutes(1));
    }

    assertThat(useCase.request(email, "vi", Optional.empty()))
        .isEqualTo(MagicLinkOutcome.ADDRESS_LIMITED);

    assertThat(mailer.sent).hasSize(3);
    clock.advance(Duration.ofMinutes(14));
    useCase.request(email, "vi", Optional.empty());
    assertThat(mailer.sent).hasSize(4);
  }

  @Test
  void first_verify_creates_the_account_and_grants_the_bonus_once() {
    Email email = new Email("a@example.com");
    useCase.request(email, "vi", Optional.empty());
    LoginResult first = useCase.verify(mailer.lastToken(), Optional.empty());
    useCase.request(email, "vi", Optional.empty());
    LoginResult second = useCase.verify(mailer.lastToken(), Optional.empty());

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.session().subject()).isEqualTo(first.session().subject());
    assertThat(grants.bonuses).containsExactly(first.session().subject().id());
    assertThat(first.session().expiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
  }

  @Test
  void issued_account_session_resolves_to_the_account() {
    useCase.request(new Email("a@example.com"), "vi", Optional.empty());
    LoginResult login = useCase.verify(mailer.lastToken(), Optional.empty());

    Subject subject = sessionUseCase.resolve(login.session().token().value());

    assertThat(subject).isInstanceOf(Subject.Account.class);
    assertThat(sessionUseCase.accountEmail(subject.id())).contains(new Email("a@example.com"));
  }

  @Test
  void token_used_twice_fails_the_second_time() {
    useCase.request(new Email("a@example.com"), "vi", Optional.empty());
    String token = mailer.lastToken();
    useCase.verify(token, Optional.empty());

    assertThatThrownBy(() -> useCase.verify(token, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void token_after_fifteen_minutes_is_expired() {
    useCase.request(new Email("a@example.com"), "vi", Optional.empty());
    String token = mailer.lastToken();
    clock.advance(Duration.ofMinutes(15).plusSeconds(60));

    assertThatThrownBy(() -> useCase.verify(token, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void unknown_or_malformed_token_is_unauthenticated() {
    assertThatThrownBy(() -> useCase.verify("nope", Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> useCase.verify(null, Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> useCase.verify("A".repeat(43), Optional.empty()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void verify_with_guest_merges_and_revokes_the_guest_sessions() {
    IssuedSession guest = guests.create();
    UUID guestId = guest.subject().id();
    useCase.request(new Email("a@example.com"), "vi", Optional.of(guestId));

    LoginResult login = useCase.verify(mailer.lastToken(), Optional.of(guestId));

    assertThat(merges.merged).hasSize(1);
    assertThat(merges.merged.get(0)).containsExactly(guestId, login.session().subject().id());
    assertThatThrownBy(() -> sessionUseCase.resolve(guest.token().value()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void guest_remembered_at_request_time_is_merged_even_if_verify_has_no_bearer() {
    UUID guestId = guests.create().subject().id();
    useCase.request(new Email("a@example.com"), "vi", Optional.of(guestId));

    LoginResult login = useCase.verify(mailer.lastToken(), Optional.empty());

    assertThat(merges.merged.get(0)).containsExactly(guestId, login.session().subject().id());
  }

  @Test
  void verify_without_guest_does_not_merge() {
    useCase.request(new Email("a@example.com"), "vi", Optional.empty());
    useCase.verify(mailer.lastToken(), Optional.empty());

    assertThat(merges.merged).isEmpty();
  }

  @Test
  void guest_sessions_last_180_days_and_expire() {
    IssuedSession guest = guests.create();

    assertThat(guest.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(180)));
    assertThat(guest.subject()).isInstanceOf(Subject.Guest.class);
    assertThat(sessions.guests).containsExactly(guest.subject().id());
    assertThat(sessionUseCase.resolve(guest.token().value())).isEqualTo(guest.subject());
    clock.advance(Duration.ofDays(181));
    assertThatThrownBy(() -> sessionUseCase.resolve(guest.token().value()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void revoke_ends_the_session() {
    IssuedSession guest = guests.create();

    sessionUseCase.revoke(guest.token().value());

    assertThatThrownBy(() -> sessionUseCase.resolve(guest.token().value()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void resolve_rejects_missing_or_garbage_bearer() {
    assertThatThrownBy(() -> sessionUseCase.resolve(null))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> sessionUseCase.resolve("garbage"))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> sessionUseCase.resolve("A".repeat(43)))
        .isInstanceOf(UnauthenticatedException.class);
  }

  /** Test clock that can be moved forward. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void daily_cap_over_all_addresses_refuses_without_sending_or_recording() {
    MagicLinkUseCase capped = useCase("http://localhost:3001", 2);
    capped.request(new Email("a@example.com"), "vi", Optional.empty());
    capped.request(new Email("b@example.com"), "vi", Optional.empty());

    assertThatThrownBy(() -> capped.request(new Email("c@example.com"), "vi", Optional.empty()))
        .isInstanceOf(MailCapReachedException.class);

    assertThat(mailer.sent).hasSize(2);
    assertThat(links.saved).hasSize(2);
  }

  @Test
  void daily_cap_is_a_rolling_day() {
    MagicLinkUseCase capped = useCase("http://localhost:3001", 1);
    capped.request(new Email("a@example.com"), "vi", Optional.empty());
    clock.advance(Duration.ofHours(23));
    assertThatThrownBy(() -> capped.request(new Email("b@example.com"), "vi", Optional.empty()))
        .isInstanceOf(MailCapReachedException.class);

    clock.advance(Duration.ofHours(1).plusSeconds(1));
    capped.request(new Email("b@example.com"), "vi", Optional.empty());

    assertThat(mailer.sent).hasSize(2);
  }

  @Test
  void one_client_gets_at_most_twenty_links_a_day_silently_and_others_still_get_theirs() {
    Optional<String> attacker = Optional.of("203.0.113.7");
    for (int i = 0; i < 25; i++) {
      useCase.request(new Email("victim" + i + "@example.com"), "vi", Optional.empty(), attacker);
    }
    assertThat(mailer.sent).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX);
    assertThat(links.saved).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX);
    assertThat(links.saved)
        .allSatisfy(
            l ->
                assertThat(l.client())
                    .isEqualTo(KEYER.key(ClientAddress.normalize("203.0.113.7").orElseThrow())));

    useCase.request(
        new Email("someone@example.com"), "vi", Optional.empty(), Optional.of("198.51.100.1"));
    useCase.request(
        new Email("nobody-known@example.com"), "vi", Optional.empty(), Optional.empty());
    assertThat(mailer.sent).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX + 2);
  }

  @Test
  void client_limit_is_a_rolling_day() {
    Optional<String> client = Optional.of("203.0.113.7");
    for (int i = 0; i < MagicLinkPolicy.CLIENT_DAILY_MAX; i++) {
      useCase.request(new Email("u" + i + "@example.com"), "vi", Optional.empty(), client);
    }
    clock.advance(Duration.ofHours(23));
    assertThat(useCase.request(new Email("late@example.com"), "vi", Optional.empty(), client))
        .isEqualTo(MagicLinkOutcome.CLIENT_LIMITED);
    assertThat(mailer.sent).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX);

    clock.advance(Duration.ofHours(1).plusSeconds(1));
    assertThat(useCase.request(new Email("late@example.com"), "vi", Optional.empty(), client))
        .isEqualTo(MagicLinkOutcome.SENT);
    assertThat(mailer.sent).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX + 1);
  }

  @Test
  void a_capped_client_is_silent_even_when_the_global_cap_is_reached() {
    MagicLinkUseCase capped = useCase("http://localhost:3001", MagicLinkPolicy.CLIENT_DAILY_MAX);
    Optional<String> client = Optional.of("203.0.113.7");
    for (int i = 0; i < MagicLinkPolicy.CLIENT_DAILY_MAX; i++) {
      capped.request(new Email("u" + i + "@example.com"), "vi", Optional.empty(), client);
    }

    capped.request(new Email("more@example.com"), "vi", Optional.empty(), client);

    assertThat(mailer.sent).hasSize(MagicLinkPolicy.CLIENT_DAILY_MAX);
  }

  @Test
  void per_address_limit_stays_silent_even_at_the_daily_cap() {
    MagicLinkUseCase capped = useCase("http://localhost:3001", 3);
    for (int i = 0; i < 3; i++) {
      capped.request(new Email("a@example.com"), "vi", Optional.empty());
    }

    capped.request(new Email("a@example.com"), "vi", Optional.empty());

    assertThat(mailer.sent).hasSize(3);
  }

  @Test
  void daily_cap_below_one_is_rejected_at_startup() {
    assertThatThrownBy(() -> useCase("http://localhost:3001", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
