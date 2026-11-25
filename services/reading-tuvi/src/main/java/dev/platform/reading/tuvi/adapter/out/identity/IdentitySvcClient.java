package dev.platform.reading.tuvi.adapter.out.identity;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.qa.ConsumeOutcome;
import dev.platform.reading.tuvi.application.qa.IdentityPort;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.application.qa.RefundOutcome;
import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * identity-svc over HTTP (connect 2 s, read 5 s). Sends {@code X-Internal-Token} on every call and
 * forwards the caller's session bearer to {@code /me} and {@code /me/profile}. Logs carry status
 * codes and exception types only: never bodies (birth data), bearers or the token.
 */
public class IdentitySvcClient implements IdentityPort {

  private static final Logger LOG = LoggerFactory.getLogger(IdentitySvcClient.class);
  private static final JsonMapper JSON = JsonMapper.shared();
  static final String INTERNAL_TOKEN = "X-Internal-Token";

  private final RestClient http;

  public IdentitySvcClient(String baseUrl, String internalToken) {
    this(builder(Duration.ofSeconds(2), Duration.ofSeconds(5)), baseUrl, internalToken);
  }

  /** For tests: a builder (e.g. bound to {@code MockRestServiceServer}). */
  IdentitySvcClient(RestClient.Builder builder, String baseUrl, String internalToken) {
    RestClient.Builder b = builder.baseUrl(baseUrl);
    if (internalToken != null && !internalToken.isBlank()) {
      b = b.defaultHeader(INTERNAL_TOKEN, internalToken.strip());
    }
    this.http = b.build();
  }

  private static RestClient.Builder builder(Duration connect, Duration read) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connect)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(read);
    return RestClient.builder().requestFactory(factory);
  }

  private record Response(int status, byte[] body) {}

  @Override
  public IdentitySubject whoAmI(String bearer) {
    Response res = get("/me", bearer);
    if (res.status() == 401) {
      throw new UnauthenticatedException();
    }
    JsonNode json = ok(res, "/me");
    return parse(
        () -> {
          String kind = json.path("kind").asString();
          String idField = "guest".equals(kind) ? "guest_id" : "account_id";
          if (!"guest".equals(kind) && !"account".equals(kind)) {
            throw new IllegalStateException("unknown subject kind");
          }
          return new IdentitySubject(kind, UUID.fromString(json.path(idField).asString()));
        });
  }

  @Override
  public Optional<Birth> profile(String bearer) {
    Response res = get("/me/profile", bearer);
    if (res.status() == 404) {
      return Optional.empty();
    }
    if (res.status() == 401) {
      throw new UnauthenticatedException();
    }
    JsonNode json = ok(res, "/me/profile");
    return parse(
        () -> {
          JsonNode b = json.path("birth");
          return Optional.of(
              new Birth(
                  text(b, "birth"), text(b, "zone"), text(b, "gender"), text(json, "calendar")));
        });
  }

  @Override
  public ConsumeOutcome consume(UUID accountId, UUID readingId, QaTopic topic) {
    String body =
        JSON.createObjectNode()
            .put("account_id", accountId.toString())
            .put("reading_id", readingId.toString())
            .put("topic", topic.name())
            .toString();
    Response res = post("/internal/credits/consume", body);
    if (res.status() == 409) {
      String reason = parse(() -> JSON.readTree(res.body()).path("reason").asString(""));
      if (reason.isBlank()) {
        throw unavailable("consume 409 without reason");
      }
      return new ConsumeOutcome.Refused(reason);
    }
    JsonNode json = ok(res, "consume");
    return parse(() -> new ConsumeOutcome.Charged(json.path("source").asString("CREDIT")));
  }

  @Override
  public RefundOutcome refund(UUID readingId) {
    String body = JSON.createObjectNode().put("reading_id", readingId.toString()).toString();
    Response res = post("/internal/credits/refund", body);
    if (res.status() == 404) {
      return RefundOutcome.NOT_CHARGED;
    }
    ok(res, "refund");
    return RefundOutcome.REFUNDED;
  }

  private Response get(String path, String bearer) {
    return call(
        () ->
            http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .accept(MediaType.APPLICATION_JSON)
                .exchange(
                    (req, res) ->
                        new Response(res.getStatusCode().value(), res.getBody().readAllBytes())));
  }

  private Response post(String path, String body) {
    return call(
        () ->
            http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange(
                    (req, res) ->
                        new Response(res.getStatusCode().value(), res.getBody().readAllBytes())));
  }

  private static Response call(java.util.function.Supplier<Response> exchange) {
    try {
      return exchange.get();
    } catch (RestClientException | IllegalStateException e) {
      // I/O failure or a connect/read timeout; the message may name the target, the type does not.
      throw unavailable("call failed: " + e.getClass().getSimpleName());
    }
  }

  private static JsonNode ok(Response res, String what) {
    if (res.status() < 200 || res.status() >= 300) {
      throw unavailable(what + " answered " + res.status());
    }
    return parse(() -> JSON.readTree(res.body()));
  }

  private static <T> T parse(java.util.function.Supplier<T> parser) {
    try {
      return parser.get();
    } catch (RuntimeException e) {
      throw unavailable("unusable body: " + e.getClass().getSimpleName());
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asString();
  }

  private static UpstreamUnavailableException unavailable(String why) {
    LOG.warn("identity-svc {}", why);
    return new UpstreamUnavailableException("identity");
  }
}
