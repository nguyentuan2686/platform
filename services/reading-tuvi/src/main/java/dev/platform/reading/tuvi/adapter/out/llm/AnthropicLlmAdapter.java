package dev.platform.reading.tuvi.adapter.out.llm;

import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmRateLimitedException;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic Messages API: {@code POST {base}/v1/messages} with {@code x-api-key}. The system prompt
 * (fixed per topic and answer kind) carries the prompt-cache breakpoint; the chart facts, passages
 * and question come after it. No sampling parameters (current models reject them). Never logs keys,
 * prompts or answers.
 */
public class AnthropicLlmAdapter implements LlmProviderPort {

  static final String API_VERSION = "2026-10-21";

  private static final Logger LOG = LoggerFactory.getLogger(AnthropicLlmAdapter.class);
  private static final JsonMapper JSON = JsonMapper.shared();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /** Raw outcome of one HTTP exchange. */
  private record Reply(int status, String retryAfter, String body) {}

  private final AnthropicSettings settings;
  private final RestClient client;
  private final String url;

  public AnthropicLlmAdapter(AnthropicSettings settings, RestClient.Builder builder) {
    this.settings = settings;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    factory.setReadTimeout(settings.timeout());
    this.client = builder.requestFactory(factory).build();
    String base = settings.baseUrl().toString();
    this.url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/messages";
  }

  @Override
  public String id() {
    return settings.id();
  }

  @Override
  public boolean paid() {
    return true;
  }

  @Override
  public boolean servesFreeTier() {
    return settings.servesFreeTier();
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
              .header("x-api-key", settings.apiKey().strip())
              .header("anthropic-version", API_VERSION)
              .contentType(MediaType.APPLICATION_JSON)
              .body(JSON.writeValueAsString(payload(request)))
              .exchange(
                  (req, res) ->
                      new Reply(
                          res.getStatusCode().value(),
                          res.getHeaders().getFirst("Retry-After"),
                          new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)),
                  true);
    } catch (RuntimeException e) {
      // Do not attach the cause: request details could carry the x-api-key header.
      throw new LlmUnavailableException(
          "llm provider " + id() + " unreachable: " + e.getClass().getSimpleName());
    }
    return map(reply);
  }

  private Map<String, Object> payload(LlmRequest request) {
    String system =
        request.messages().stream()
            .filter(m -> m.role() == LlmMessage.Role.SYSTEM)
            .map(LlmMessage::content)
            .collect(Collectors.joining("\n\n"));
    List<Map<String, String>> messages = new ArrayList<>();
    for (LlmMessage m : request.messages()) {
      if (m.role() != LlmMessage.Role.SYSTEM) {
        messages.add(
            Map.of("role", m.role().name().toLowerCase(Locale.ROOT), "content", m.content()));
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", settings.model());
    body.put("max_tokens", request.maxTokens() + settings.thinkingHeadroomTokens());
    if (!system.isEmpty()) {
      Map<String, Object> block = new LinkedHashMap<>();
      block.put("type", "text");
      block.put("text", system);
      if (settings.promptCache()) {
        block.put("cache_control", Map.of("type", "ephemeral"));
      }
      body.put("system", List.of(block));
    }
    body.put("messages", messages);
    body.put("output_config", Map.of("effort", settings.effort()));
    return body;
  }

  private LlmCompletion map(Reply reply) {
    int status = reply.status();
    if (status == 429) {
      throw new LlmRateLimitedException(parseRetryAfter(reply.retryAfter()));
    }
    if (status >= 500) {
      throw new LlmUnavailableException("llm provider " + id() + " returned " + status);
    }
    if (status >= 400) {
      LOG.error("llm provider rejected request provider={} status={}", id(), status);
      throw new LlmUnavailableException("llm provider " + id() + " rejected request");
    }
    if (status != 200) {
      throw new LlmUnavailableException("llm provider " + id() + " returned " + status);
    }
    return parse(reply.body());
  }

  private LlmCompletion parse(String body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException e) {
      throw new LlmUnavailableException("llm provider " + id() + " returned malformed json");
    }
    StringBuilder text = new StringBuilder();
    for (JsonNode block : root.path("content")) {
      if (block.path("type").asString("").equals("text")) {
        text.append(block.path("text").asString(""));
      }
    }
    JsonNode usage = root.path("usage");
    int write = usage.path("cache_creation_input_tokens").asInt(0);
    int read = usage.path("cache_read_input_tokens").asInt(0);
    LlmCompletion out =
        new LlmCompletion(
            text.toString(),
            id(),
            usage.path("input_tokens").asInt(0) + write + read,
            usage.path("output_tokens").asInt(0),
            write,
            read);
    String stop = root.path("stop_reason").asString("");
    if (stop.equals("refusal")) {
      LOG.warn(
          "llm provider refused provider={} category={}",
          id(),
          root.path("stop_details").path("category").asString("unknown"));
      throw new LlmUnavailableException("llm provider " + id() + " refused", out);
    }
    // Thinking shares max_tokens: a cut-off reply is never served as a complete answer.
    if (stop.equals("max_tokens") || out.text().isBlank()) {
      LOG.warn("llm provider reply unusable provider={} stop_reason={}", id(), stop);
      throw new LlmUnavailableException(
          "llm provider " + id() + " returned no complete text (stop_reason " + stop + ")", out);
    }
    return out;
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
