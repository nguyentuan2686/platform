package dev.platform.reading.tuvi.adapter.out.llm;

import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmRateLimitedException;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Groq, Gemini (OpenAI-compatible endpoint) and the paid provider: {@code POST
 * {base}/chat/completions} with a Bearer key. Never logs keys, prompts or answers.
 */
public class OpenAiCompatibleLlmAdapter implements LlmProviderPort {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleLlmAdapter.class);
  private static final JsonMapper JSON = JsonMapper.shared();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /** Raw outcome of one HTTP exchange. */
  private record Reply(int status, String retryAfter, String body) {}

  private final OpenAiCompatibleSettings settings;
  private final RestClient client;
  private final String url;

  public OpenAiCompatibleLlmAdapter(OpenAiCompatibleSettings settings, RestClient.Builder builder) {
    this.settings = settings;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    factory.setReadTimeout(settings.timeout());
    this.client = builder.requestFactory(factory).build();
    String base = settings.baseUrl().toString();
    this.url =
        (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions";
  }

  @Override
  public String id() {
    return settings.id();
  }

  @Override
  public boolean paid() {
    return settings.paid();
  }

  @Override
  public int dailyRequestLimit() {
    return settings.dailyRequestLimit();
  }

  @Override
  public LlmCompletion complete(LlmRequest request) {
    Reply reply;
    try {
      reply =
          client
              .post()
              .uri(url)
              .header("Authorization", "Bearer " + settings.apiKey().strip())
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(JSON.writeValueAsString(payload(request)))
              .exchange(
                  (req, res) ->
                      new Reply(
                          res.getStatusCode().value(),
                          res.getHeaders().getFirst("Retry-After"),
                          new String(
                              res.getBody().readAllBytes(),
                              java.nio.charset.StandardCharsets.UTF_8)),
                  true);
    } catch (RuntimeException e) {
      // Do not attach the cause: request details could carry the Authorization header.
      throw new LlmUnavailableException(
          "llm provider " + id() + " unreachable: " + e.getClass().getSimpleName());
    }
    return map(reply);
  }

  private Map<String, Object> payload(LlmRequest request) {
    List<Map<String, String>> messages =
        request.messages().stream()
            .map(m -> Map.of("role", role(m.role()), "content", m.content()))
            .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", settings.model());
    body.put("messages", messages);
    body.put("max_tokens", request.maxTokens());
    body.put("temperature", request.temperature());
    body.put("stream", false);
    return body;
  }

  private static String role(LlmMessage.Role role) {
    return role.name().toLowerCase(Locale.ROOT);
  }

  private LlmCompletion map(Reply reply) {
    int status = reply.status();
    if (status == 429) {
      throw new LlmRateLimitedException(parseRetryAfter(reply.retryAfter()));
    }
    if (status >= 500) {
      throw new LlmUnavailableException("llm provider " + id() + " returned " + status);
    }
    if (status == 400 || status == 401 || status == 403 || status == 404) {
      LOG.error("llm provider rejected request provider={} status={}", id(), status);
      throw new LlmUnavailableException("llm provider " + id() + " rejected request");
    }
    if (status != 200) {
      throw new LlmUnavailableException("llm provider " + id() + " returned " + status);
    }
    return parse(reply.body());
  }

  private LlmCompletion parse(String body) {
    try {
      JsonNode root = JSON.readTree(body);
      String text = root.path("choices").path(0).path("message").path("content").asString("");
      if (text.isBlank()) {
        throw new LlmUnavailableException("llm provider " + id() + " returned no content");
      }
      JsonNode usage = root.path("usage");
      return new LlmCompletion(
          text,
          id(),
          usage.path("prompt_tokens").asInt(0),
          usage.path("completion_tokens").asInt(0));
    } catch (JacksonException e) {
      throw new LlmUnavailableException("llm provider " + id() + " returned malformed json");
    }
  }

  private static Optional<Duration> parseRetryAfter(String header) {
    if (header == null) {
      return Optional.empty();
    }
    try {
      long seconds = Long.parseLong(header.strip());
      return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
