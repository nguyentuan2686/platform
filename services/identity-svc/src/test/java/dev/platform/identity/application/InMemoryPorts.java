package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import dev.platform.identity.domain.Subject;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory fakes of every application port, shared by the application tests. */
final class InMemoryPorts {

  private InMemoryPorts() {}

  static final class Accounts implements AccountRepositoryPort {
    final Map<String, UUID> byEmail = new HashMap<>();

    @Override
    public Upserted upsert(Email email, Instant now) {
      UUID existing = byEmail.get(email.value());
      if (existing != null) {
        return new Upserted(existing, false);
      }
      UUID id = UUID.randomUUID();
      byEmail.put(email.value(), id);
      return new Upserted(id, true);
    }

    @Override
    public Optional<Email> findEmail(UUID accountId) {
      return byEmail.entrySet().stream()
          .filter(e -> e.getValue().equals(accountId))
          .findFirst()
          .map(e -> new Email(e.getKey()));
    }

    final Map<String, UUID> byGoogleSub = new HashMap<>();
    UUID conflictOnNextLink;
    boolean alwaysConflict;

    @Override
    public Optional<UUID> findByGoogleSub(String sub) {
      return Optional.ofNullable(byGoogleSub.get(sub));
    }

    @Override
    public boolean linkGoogleSub(UUID accountId, String sub) {
      if (alwaysConflict) {
        throw new AccountConflictException();
      }
      if (conflictOnNextLink != null) {
        byGoogleSub.put(sub, conflictOnNextLink);
        conflictOnNextLink = null;
        throw new AccountConflictException();
      }
      if (byGoogleSub.containsValue(accountId) || byGoogleSub.containsKey(sub)) {
        return false;
      }
      byGoogleSub.put(sub, accountId);
      return true;
    }
  }

  static final class OidcLogins implements OidcLoginRepositoryPort {
    final Map<String, PendingOidcLogin> saved = new HashMap<>();
    final Set<String> used = new HashSet<>();

    @Override
    public void save(PendingOidcLogin login) {
      saved.put(login.state(), login);
    }

    @Override
    public Optional<PendingOidcLogin> consume(String state, Instant now) {
      PendingOidcLogin login = saved.get(state);
      if (login == null || used.contains(state) || !login.expiresAt().isAfter(now)) {
        return Optional.empty();
      }
      used.add(state);
      return Optional.of(login);
    }
  }

  static final class FakeOidc implements OidcPort {
    IdTokenClaims claims;
    RuntimeException failure;
    final List<String> exchangedCodes = new ArrayList<>();
    final List<String> exchangedVerifiers = new ArrayList<>();
    final List<URI> exchangedRedirects = new ArrayList<>();
    String lastState;
    String lastNonce;
    String lastChallenge;
    URI lastRedirect;
    boolean enabled = true;

    @Override
    public boolean enabled() {
      return enabled;
    }

    @Override
    public URI authorizationUri(String state, String nonce, String codeChallenge, URI redirectUri) {
      lastState = state;
      lastNonce = nonce;
      lastChallenge = codeChallenge;
      lastRedirect = redirectUri;
      return URI.create("https://idp.example/authorize?state=" + state);
    }

    @Override
    public IdTokenClaims exchange(String code, String codeVerifier, URI redirectUri) {
      exchangedCodes.add(code);
      exchangedVerifiers.add(codeVerifier);
      exchangedRedirects.add(redirectUri);
      if (failure != null) {
        throw failure;
      }
      return claims;
    }
  }

  static final class Sessions implements SessionRepositoryPort {
    final Set<UUID> guests = new HashSet<>();
    final List<StoredSession> stored = new ArrayList<>();
    final Map<UUID, Instant> revoked = new HashMap<>();

    @Override
    public void createGuest(UUID id, Instant now) {
      guests.add(id);
    }

    @Override
    public void save(StoredSession session) {
      stored.add(session);
    }

    @Override
    public Optional<Subject> findActive(byte[] tokenSha256, Instant now) {
      return stored.stream()
          .filter(s -> Arrays.equals(s.tokenSha256(), tokenSha256))
          .filter(s -> !revoked.containsKey(s.id()) && s.expiresAt().isAfter(now))
          .findFirst()
          .map(StoredSession::subject);
    }

    @Override
    public void revoke(byte[] tokenSha256, Instant now) {
      stored.stream()
          .filter(s -> Arrays.equals(s.tokenSha256(), tokenSha256))
          .forEach(s -> revoked.putIfAbsent(s.id(), now));
    }

    @Override
    public void revokeGuestSessions(UUID guestId, Instant now) {
      stored.stream()
          .filter(s -> s.subject() instanceof Subject.Guest g && g.id().equals(guestId))
          .forEach(s -> revoked.putIfAbsent(s.id(), now));
    }
  }

  static final class MagicLinks implements MagicLinkRepositoryPort {
    final List<PendingMagicLink> saved = new ArrayList<>();
    final Set<UUID> used = new HashSet<>();

    @Override
    public void lock(Email email) {}

    @Override
    public void save(PendingMagicLink link) {
      saved.add(link);
    }

    @Override
    public List<Instant> requestTimesSince(Email email, Instant since) {
      return saved.stream()
          .filter(l -> l.email().equals(email) && l.createdAt().isAfter(since))
          .map(PendingMagicLink::createdAt)
          .toList();
    }

    @Override
    public Optional<ConsumedMagicLink> consume(byte[] tokenSha256, Instant now) {
      return saved.stream()
          .filter(l -> Arrays.equals(l.tokenSha256(), tokenSha256))
          .filter(l -> !used.contains(l.id()) && l.expiresAt().isAfter(now))
          .findFirst()
          .map(
              l -> {
                used.add(l.id());
                return new ConsumedMagicLink(l.email(), l.guestId());
              });
    }
  }

  static final class Mailer implements MailerPort {
    record Sent(Email to, URI link, String lang) {}

    final List<Sent> sent = new ArrayList<>();

    @Override
    public void sendMagicLink(Email to, URI link, String lang) {
      sent.add(new Sent(to, link, lang));
    }

    /** The raw token from the most recent mail's link. */
    String lastToken() {
      String query = sent.get(sent.size() - 1).link().getRawQuery();
      return query.substring("token=".length());
    }
  }

  static final class Merges implements AccountMergePort {
    final List<UUID[]> merged = new ArrayList<>();

    @Override
    public void mergeGuest(UUID guestId, UUID accountId) {
      merged.add(new UUID[] {guestId, accountId});
    }
  }

  static final class Grants implements EntitlementGrantPort {
    final List<UUID> bonuses = new ArrayList<>();

    @Override
    public void signupBonus(UUID accountId) {
      bonuses.add(accountId);
    }
  }
}
