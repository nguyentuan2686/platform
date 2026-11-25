package dev.platform.identity.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal HTTP client against a running identity-svc, for the Testcontainers ITs. */
public final class HttpIdentity {

  /** Magic links captured by the recording mailer (the mailer is replaced in the ITs). */
  public static final List<URI> LINKS = new CopyOnWriteArrayList<>();

  public static final String PROFILE_BODY =
      "{\"label\":\"Me\",\"birth\":{\"birth\":\"1994-03-12T05:30\",\"zone\":\"Asia/Ho_Chi_Minh\","
          + "\"gender\":\"female\"},\"calendar\":\"VN\"}";

  /** A logged-in account. */
  public record Login(UUID accountId, String token) {}

  private final int port;
  private final HttpClient http = HttpClient.newHttpClient();

  public HttpIdentity(int port) {
    this.port = port;
  }

  public HttpResponse<String> send(String method, String path, String bearer, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  public static JsonNode json(HttpResponse<String> response) {
    return JsonMapper.shared().readTree(response.body());
  }

  public String guestToken() throws Exception {
    return json(send("POST", "/guests", null, null)).get("session_token").asString();
  }

  /** Logs in by magic link (a new email gets the signup bonus). */
  public Login login(String email) throws Exception {
    send("POST", "/auth/magic-links", null, "{\"email\":\"" + email + "\"}");
    String query = LINKS.get(LINKS.size() - 1).getRawQuery();
    String token = query.substring(query.indexOf("token=") + "token=".length());
    JsonNode session =
        json(send("POST", "/auth/magic-links/verify", null, "{\"token\":\"" + token + "\"}"));
    return new Login(
        UUID.fromString(session.get("account_id").asString()),
        session.get("session_token").asString());
  }

  public HttpResponse<String> consume(UUID accountId, UUID readingId, String topic)
      throws Exception {
    return send(
        "POST",
        "/internal/credits/consume",
        null,
        "{\"account_id\":\""
            + accountId
            + "\",\"reading_id\":\""
            + readingId
            + "\",\"topic\":\""
            + topic
            + "\"}");
  }

  public int credits(Login login) throws Exception {
    return json(send("GET", "/me/entitlements", login.token(), null)).get("credits").asInt();
  }
}
