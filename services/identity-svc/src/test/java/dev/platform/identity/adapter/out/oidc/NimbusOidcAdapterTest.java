package dev.platform.identity.adapter.out.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import dev.platform.identity.application.IdTokenClaims;
import dev.platform.identity.application.OidcUnavailableException;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.support.FakeIdp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NimbusOidcAdapterTest {

  static final Instant T0 = Instant.parse("2026-11-25T15:00:00Z");
  static final String CLIENT = "local-test-client";
  static final URI REDIRECT = URI.create("http://localhost:3001/vi/dang-nhap/google");

  FakeIdp idp;
  NimbusOidcAdapter adapter;

  @BeforeEach
  void setUp() {
    idp = new FakeIdp();
    adapter =
        new NimbusOidcAdapter(
            idp.issuer(), CLIENT, "local-test-secret", Clock.fixed(T0, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    idp.close();
  }

  private JWTClaimsSet.Builder claims() {
    return idp.validClaims(CLIENT, "nonce-1", Date.from(T0));
  }

  private IdTokenClaims exchange(RSAKey key, JWTClaimsSet claims) {
    idp.respondWithIdToken(FakeIdp.sign(key, claims));
    return adapter.exchange("the-code", "the-verifier", REDIRECT);
  }

  @Test
  void valid_token_yields_claims_and_posts_code_pkce_and_client_credentials() {
    IdTokenClaims claims = exchange(FakeIdp.KEY, claims().build());

    assertThat(claims.subject()).isEqualTo("google-sub-1");
    assertThat(claims.email().value()).isEqualTo("user@example.com");
    assertThat(claims.emailVerified()).isTrue();
    assertThat(claims.nonce()).isEqualTo("nonce-1");
    assertThat(idp.tokenRequests()).hasSize(1);
    assertThat(idp.tokenRequests().get(0))
        .containsEntry("grant_type", "authorization_code")
        .containsEntry("code", "the-code")
        .containsEntry("code_verifier", "the-verifier")
        .containsEntry("redirect_uri", REDIRECT.toString())
        .containsEntry("client_id", CLIENT)
        .containsEntry("client_secret", "local-test-secret");
  }

  @Test
  void claims_never_print_their_content() {
    IdTokenClaims claims = exchange(FakeIdp.KEY, claims().build());

    assertThat(claims.toString()).doesNotContain("user@example.com").doesNotContain("google-sub-1");
  }

  @Test
  void email_verified_given_as_string_is_understood() {
    IdTokenClaims claims = exchange(FakeIdp.KEY, claims().claim("email_verified", "true").build());

    assertThat(claims.emailVerified()).isTrue();
  }

  @Test
  void unverified_email_is_reported_not_rejected_here() {
    IdTokenClaims claims = exchange(FakeIdp.KEY, claims().claim("email_verified", false).build());

    assertThat(claims.emailVerified()).isFalse();
  }

  @Test
  void wrong_audience_is_rejected() {
    assertThatThrownBy(() -> exchange(FakeIdp.KEY, claims().audience("someone-else").build()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void several_audiences_need_a_matching_authorized_party() {
    JWTClaimsSet.Builder multi = claims().audience(List.of(CLIENT, "other"));

    assertThatThrownBy(() -> exchange(FakeIdp.KEY, multi.build()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(exchange(FakeIdp.KEY, multi.claim("azp", CLIENT).build()).subject())
        .isEqualTo("google-sub-1");
  }

  @Test
  void wrong_issuer_is_rejected() {
    assertThatThrownBy(() -> exchange(FakeIdp.KEY, claims().issuer("https://evil.example").build()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void expired_token_is_rejected_beyond_the_skew_but_accepted_within_it() {
    Date expiredLongAgo = Date.from(T0.minusSeconds(120));
    Date expiredJustNow = Date.from(T0.minusSeconds(30));

    assertThatThrownBy(() -> exchange(FakeIdp.KEY, claims().expirationTime(expiredLongAgo).build()))
        .isInstanceOf(UnauthenticatedException.class);
    assertThat(exchange(FakeIdp.KEY, claims().expirationTime(expiredJustNow).build()).subject())
        .isEqualTo("google-sub-1");
  }

  @Test
  void token_issued_in_the_future_is_rejected() {
    assertThatThrownBy(
            () -> exchange(FakeIdp.KEY, claims().issueTime(Date.from(T0.plusSeconds(600))).build()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void token_without_expiry_is_rejected() {
    JWTClaimsSet noExp = new JWTClaimsSet.Builder(claims().build()).expirationTime(null).build();

    assertThatThrownBy(() -> exchange(FakeIdp.KEY, noExp))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void token_signed_by_another_key_is_rejected() {
    assertThatThrownBy(() -> exchange(FakeIdp.OTHER_KEY, claims().build()))
        .isInstanceOf(UnauthenticatedException.class);
    // The unknown kid triggered one refetch, not a loop.
    assertThat(idp.jwksFetches()).isLessThanOrEqualTo(2);
  }

  @Test
  void token_signed_with_the_published_kid_but_a_different_key_is_rejected() throws Exception {
    RSAKey impostor = new RSAKeyGenerator(2048).keyID(FakeIdp.KEY.getKeyID()).generate();

    assertThatThrownBy(() -> exchange(impostor, claims().build()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void unsigned_token_is_rejected() {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        b64.encodeToString(claims().build().toString().getBytes(StandardCharsets.UTF_8));
    idp.respondWithIdToken(header + "." + payload + ".");

    assertThatThrownBy(() -> adapter.exchange("c", "v", REDIRECT))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void rotated_signing_key_is_picked_up_by_one_refetch() {
    exchange(FakeIdp.KEY, claims().build());
    assertThat(idp.jwksFetches()).isEqualTo(1);

    idp.publish(FakeIdp.OTHER_KEY);
    IdTokenClaims claims = exchange(FakeIdp.OTHER_KEY, claims().build());

    assertThat(claims.subject()).isEqualTo("google-sub-1");
    assertThat(idp.jwksFetches()).isEqualTo(2);
  }

  @Test
  void jwks_and_discovery_are_cached_between_exchanges() {
    exchange(FakeIdp.KEY, claims().build());
    exchange(FakeIdp.KEY, claims().build());

    assertThat(idp.jwksFetches()).isEqualTo(1);
    assertThat(idp.discoveryFetches()).isEqualTo(1);
  }

  @Test
  void token_endpoint_rejection_is_unauthenticated() {
    idp.respondWithStatus(400);

    assertThatThrownBy(() -> adapter.exchange("bad", "v", REDIRECT))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void token_endpoint_outage_is_unavailable() {
    idp.respondWithStatus(503);

    assertThatThrownBy(() -> adapter.exchange("c", "v", REDIRECT))
        .isInstanceOf(OidcUnavailableException.class);
  }

  @Test
  void unreachable_provider_is_unavailable() {
    idp.close();

    assertThatThrownBy(() -> adapter.exchange("c", "v", REDIRECT))
        .isInstanceOf(OidcUnavailableException.class);
  }

  @Test
  void malformed_or_missing_email_is_rejected() {
    JWTClaimsSet bad =
        new JWTClaimsSet.Builder(claims().build()).claim("email", "not an email").build();
    JWTClaimsSet missing = new JWTClaimsSet.Builder(claims().build()).claim("email", null).build();

    assertThatThrownBy(() -> exchange(FakeIdp.KEY, bad))
        .isInstanceOf(UnauthenticatedException.class);
    assertThatThrownBy(() -> exchange(FakeIdp.KEY, missing))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void authorization_uri_carries_the_code_and_pkce_parameters() {
    URI uri = adapter.authorizationUri("st", "no", "chal", REDIRECT);

    assertThat(uri.toString()).startsWith(idp.issuer() + "/authorize?");
    assertThat(uri.getRawQuery().split("&"))
        .contains(
            "response_type=code",
            "client_id=" + CLIENT,
            "scope=openid%20email",
            "state=st",
            "nonce=no",
            "code_challenge=chal",
            "code_challenge_method=S256",
            "redirect_uri=http%3A%2F%2Flocalhost%3A3001%2Fvi%2Fdang-nhap%2Fgoogle");
  }

  @Test
  void discovery_document_of_another_issuer_is_refused() {
    NimbusOidcAdapter other =
        new NimbusOidcAdapter(
            URI.create(idp.issuer() + "/other"), CLIENT, "s", Clock.fixed(T0, ZoneOffset.UTC));

    assertThatThrownBy(() -> other.authorizationUri("s", "n", "c", REDIRECT))
        .isInstanceOf(OidcUnavailableException.class);
  }
}
