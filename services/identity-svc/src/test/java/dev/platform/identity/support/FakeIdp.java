package dev.platform.identity.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal OpenID provider for tests: discovery, JWKS and token endpoints on a loopback port,
 * signing ID tokens with RSA keys generated once per test run.
 */
public final class FakeIdp implements AutoCloseable {

  public static final RSAKey KEY = generate("test-key-1");
  public static final RSAKey OTHER_KEY = generate("other-key");

  private final HttpServer server;
  private final String issuer;
  private volatile RSAKey publishedKey = KEY;
  private volatile String nextIdToken = "";
  private volatile int tokenStatus = 200;
  private final AtomicInteger jwksFetches = new AtomicInteger();
  private final AtomicInteger discoveryFetches = new AtomicInteger();
  private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

  public FakeIdp() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/.well-known/openid-configuration", this::discovery);
    server.createContext("/jwks", this::jwks);
    server.createContext("/token", this::token);
    server.start();
  }

  private static RSAKey generate(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public URI issuer() {
    return URI.create(issuer);
  }

  public void publish(RSAKey key) {
    this.publishedKey = key;
  }

  public void respondWithIdToken(String idToken) {
    this.nextIdToken = idToken;
  }

  public void respondWithStatus(int status) {
    this.tokenStatus = status;
  }

  public int jwksFetches() {
    return jwksFetches.get();
  }

  public int discoveryFetches() {
    return discoveryFetches.get();
  }

  public List<Map<String, String>> tokenRequests() {
    return tokenRequests;
  }

  /** Claims that a well-behaved provider would issue for this client and nonce. */
  public JWTClaimsSet.Builder validClaims(String clientId, String nonce, Date now) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(clientId)
        .subject("google-sub-1")
        .claim("email", "user@example.com")
        .claim("email_verified", true)
        .claim("nonce", nonce)
        .issueTime(now)
        .expirationTime(new Date(now.getTime() + 3_600_000));
  }

  public static String sign(RSAKey key, JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private void discovery(HttpExchange ex) throws IOException {
    discoveryFetches.incrementAndGet();
    reply(
        ex,
        200,
        "{\"issuer\":\""
            + issuer
            + "\",\"authorization_endpoint\":\""
            + issuer
            + "/authorize\",\"token_endpoint\":\""
            + issuer
            + "/token\",\"jwks_uri\":\""
            + issuer
            + "/jwks\"}");
  }

  private void jwks(HttpExchange ex) throws IOException {
    jwksFetches.incrementAndGet();
    reply(ex, 200, new JWKSet(publishedKey.toPublicJWK()).toString());
  }

  private void token(HttpExchange ex) throws IOException {
    String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : form.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    tokenRequests.add(params);
    if (tokenStatus != 200) {
      reply(ex, tokenStatus, "{\"error\":\"invalid_grant\"}");
      return;
    }
    reply(
        ex,
        200,
        "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"id_token\":\"" + nextIdToken + "\"}");
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
