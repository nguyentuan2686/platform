package dev.platform.identity.adapter.out.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.platform.identity.application.IdTokenClaims;
import dev.platform.identity.application.OidcPort;
import dev.platform.identity.application.OidcUnavailableException;
import dev.platform.identity.application.UnauthenticatedException;
import dev.platform.identity.domain.Email;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google (any OpenID provider) over plain HTTP plus Nimbus JOSE for the ID token. Discovery and the
 * signing keys are cached; an unknown {@code kid} triggers one refetch. Nothing from the provider
 * (tokens, codes, claims) is ever logged.
 */
public class NimbusOidcAdapter implements OidcPort {

  static final Duration SKEW = Duration.ofSeconds(60);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final String issuer;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  private volatile Discovery discovery;
  private volatile JWKSet jwks;

  public NimbusOidcAdapter(URI issuer, String clientId, String clientSecret, Clock clock) {
    this.issuer = issuer.toString();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
  }

  private record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public URI authorizationUri(String state, String nonce, String codeChallenge, URI redirectUri) {
    String endpoint = discovery().authorizationEndpoint();
    Map<String, String> query = new LinkedHashMap<>();
    query.put("response_type", "code");
    query.put("client_id", clientId);
    query.put("redirect_uri", redirectUri.toString());
    query.put("scope", "openid email");
    query.put("state", state);
    query.put("nonce", nonce);
    query.put("code_challenge", codeChallenge);
    query.put("code_challenge_method", "S256");
    return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + form(query));
  }

  @Override
  public IdTokenClaims exchange(String code, String codeVerifier, URI redirectUri) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("grant_type", "authorization_code");
    params.put("code", code);
    params.put("redirect_uri", redirectUri.toString());
    params.put("client_id", clientId);
    params.put("client_secret", clientSecret);
    params.put("code_verifier", codeVerifier);
    String response = postForm(URI.create(discovery().tokenEndpoint()), form(params));
    String idToken;
    try {
      Object value = JSONObjectUtils.parse(response).get("id_token");
      if (!(value instanceof String s) || s.isBlank()) {
        throw new UnauthenticatedException();
      }
      idToken = s;
    } catch (ParseException e) {
      throw new UnauthenticatedException();
    }
    return verify(idToken);
  }

  private IdTokenClaims verify(String idToken) {
    try {
      SignedJWT jwt = SignedJWT.parse(idToken);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
          || jwt.getHeader().getKeyID() == null) {
        throw new UnauthenticatedException();
      }
      JWK key = signingKey(jwt.getHeader().getKeyID());
      if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) {
        throw new UnauthenticatedException();
      }
      return claimsOf(jwt.getJWTClaimsSet());
    } catch (ParseException | JOSEException e) {
      throw new UnauthenticatedException();
    }
  }

  private IdTokenClaims claimsOf(JWTClaimsSet claims) {
    Instant now = clock.instant();
    List<String> audience = claims.getAudience();
    boolean valid =
        issuer.equals(claims.getIssuer())
            && audience != null
            && audience.contains(clientId)
            && (audience.size() == 1 || clientId.equals(claims.getClaim("azp")))
            && claims.getExpirationTime() != null
            && claims.getExpirationTime().toInstant().plus(SKEW).isAfter(now)
            && (claims.getIssueTime() == null
                || !claims.getIssueTime().toInstant().minus(SKEW).isAfter(now))
            && claims.getSubject() != null
            && !claims.getSubject().isBlank();
    if (!valid) {
      throw new UnauthenticatedException();
    }
    Object email = claims.getClaim("email");
    Object verified = claims.getClaim("email_verified");
    Object nonce = claims.getClaim("nonce");
    if (!(email instanceof String address)) {
      throw new UnauthenticatedException();
    }
    try {
      return new IdTokenClaims(
          claims.getSubject(),
          new Email(address),
          Boolean.TRUE.equals(verified) || "true".equals(verified),
          nonce instanceof String n ? n : null);
    } catch (IllegalArgumentException e) {
      throw new UnauthenticatedException();
    }
  }

  private JWK signingKey(String kid) {
    JWKSet set = jwks;
    boolean fresh = false;
    if (set == null) {
      set = fetchJwks();
      fresh = true;
    }
    JWK key = set.getKeyByKeyId(kid);
    if (key == null && !fresh) {
      key = fetchJwks().getKeyByKeyId(kid);
    }
    if (key == null) {
      throw new UnauthenticatedException();
    }
    return key;
  }

  private synchronized JWKSet fetchJwks() {
    try {
      JWKSet fetched = JWKSet.parse(get(URI.create(discovery().jwksUri())));
      jwks = fetched;
      return fetched;
    } catch (ParseException e) {
      throw OidcUnavailableException.unreachable(e);
    }
  }

  private Discovery discovery() {
    Discovery cached = discovery;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (discovery == null) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try {
          Map<String, Object> doc =
              JSONObjectUtils.parse(get(URI.create(base + "/.well-known/openid-configuration")));
          if (!issuer.equals(doc.get("issuer"))) {
            throw OidcUnavailableException.unreachable(null);
          }
          discovery =
              new Discovery(
                  required(doc, "authorization_endpoint"),
                  required(doc, "token_endpoint"),
                  required(doc, "jwks_uri"));
        } catch (ParseException e) {
          throw OidcUnavailableException.unreachable(e);
        }
      }
      return discovery;
    }
  }

  private static String required(Map<String, Object> doc, String name) {
    if (doc.get(name) instanceof String s && !s.isBlank()) {
      return s;
    }
    throw OidcUnavailableException.unreachable(null);
  }

  private String get(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 200) {
      throw OidcUnavailableException.unreachable(null);
    }
    return response.body();
  }

  private String postForm(URI uri, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = send(request);
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return response.body();
    }
    if (status >= 400 && status < 500) {
      throw new UnauthenticatedException();
    }
    throw OidcUnavailableException.unreachable(null);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw OidcUnavailableException.unreachable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw OidcUnavailableException.unreachable(e);
    }
  }

  private static String form(Map<String, String> params) {
    return params.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
