package dev.platform.reading.tuvi.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmRateLimitedException;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmTier;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import dev.platform.reading.tuvi.support.LlmStubServer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiCompatibleLlmAdapterTest {

  static final String KEY = "local-test-groq-key";
  static final LlmRequest REQ =
      new LlmRequest(
          List.of(
              new LlmMessage(LlmMessage.Role.SYSTEM, "sys"),
              new LlmMessage(LlmMessage.Role.USER, "hello")),
          321,
          0.4,
          LlmTier.ANY);

  LlmStubServer stub;

  @BeforeEach
  void start() {
    stub = new LlmStubServer();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  static String fixture(String name) throws IOException {
    try (var in = OpenAiCompatibleLlmAdapterTest.class.getResourceAsStream("/llm/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  OpenAiCompatibleLlmAdapter adapter(URI base, Duration timeout, boolean paid) {
    return new OpenAiCompatibleLlmAdapter(
        new OpenAiCompatibleSettings("groq", base, KEY, "test-model", 900, paid, timeout),
        RestClient.builder());
  }

  OpenAiCompatibleLlmAdapter adapter() {
    return adapter(stub.baseUrl(), Duration.ofSeconds(5), false);
  }

  @Test
  void sends_openai_shape() throws Exception {
    stub.body = fixture("completion-ok.json");

    adapter().complete(REQ);

    var rec = stub.requests().get(0);
    assertThat(rec.method()).isEqualTo("POST");
    assertThat(rec.path()).isEqualTo("/chat/completions");
    assertThat(rec.headers().get("authorization")).isEqualTo("Bearer " + KEY);
    JsonNode json = JsonMapper.shared().readTree(rec.body());
    assertThat(json.get("model").asString()).isEqualTo("test-model");
    assertThat(json.get("max_tokens").asInt()).isEqualTo(321);
    assertThat(json.get("temperature").asDouble()).isEqualTo(0.4);
    assertThat(json.get("stream").asBoolean()).isFalse();
    assertThat(json.get("messages").get(0).get("role").asString()).isEqualTo("system");
    assertThat(json.get("messages").get(1).get("role").asString()).isEqualTo("user");
    assertThat(json.get("messages").get(1).get("content").asString()).isEqualTo("hello");
  }

  @Test
  void parses_content_and_usage() throws Exception {
    stub.body = fixture("completion-ok.json");

    var out = adapter().complete(REQ);

    assertThat(out.text()).isEqualTo("Chào con.");
    assertThat(out.provider()).isEqualTo("groq");
    assertThat(out.promptTokens()).isEqualTo(12);
    assertThat(out.completionTokens()).isEqualTo(7);
  }

  @Test
  void missing_usage_is_zero() {
    stub.body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    var out = adapter().complete(REQ);

    assertThat(out.promptTokens()).isZero();
    assertThat(out.completionTokens()).isZero();
  }

  @Test
  void paid_flag_and_limit_come_from_settings() {
    var a = adapter(stub.baseUrl(), Duration.ofSeconds(1), true);

    assertThat(a.paid()).isTrue();
    assertThat(a.id()).isEqualTo("groq");
    assertThat(a.dailyRequestLimit()).isEqualTo(900);
  }

  @Test
  void rate_limited_with_retry_after() {
    stub.status = 429;
    stub.responseHeaders = java.util.Map.of("Retry-After", "20");

    assertThatThrownBy(() -> adapter().complete(REQ))
        .isInstanceOfSatisfying(
            LlmRateLimitedException.class,
            e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(20)));
  }

  @Test
  void rate_limited_without_header() {
    stub.status = 429;

    assertThatThrownBy(() -> adapter().complete(REQ))
        .isInstanceOfSatisfying(
            LlmRateLimitedException.class, e -> assertThat(e.retryAfter()).isEmpty());
  }

  @Test
  void non_numeric_retry_after_is_ignored() {
    stub.status = 429;
    stub.responseHeaders = java.util.Map.of("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT");

    assertThatThrownBy(() -> adapter().complete(REQ))
        .isInstanceOfSatisfying(
            LlmRateLimitedException.class, e -> assertThat(e.retryAfter()).isEmpty());
  }

  @Test
  void server_error_is_unavailable() {
    stub.status = 503;

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void timeout_is_unavailable() {
    stub.delayMillis = 1500;
    stub.body = "{}";
    var slow = adapter(stub.baseUrl(), Duration.ofMillis(200), false);

    assertThatThrownBy(() -> slow.complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void connection_refused_is_unavailable() {
    URI dead = stub.baseUrl();
    stub.close();

    assertThatThrownBy(() -> adapter(dead, Duration.ofSeconds(1), false).complete(REQ))
        .isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void malformed_json_is_unavailable() {
    stub.body = "not json";

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void blank_content_is_unavailable() throws Exception {
    stub.body = fixture("completion-empty.json");

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void client_error_logs_status_not_key(CapturedOutput output) {
    stub.status = 401;
    stub.body = "{\"error\":\"bad key " + KEY + "\"}";

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);

    assertThat(output.getAll())
        .contains("llm provider rejected request")
        .contains("provider=groq")
        .contains("status=401")
        .doesNotContain(KEY);
  }

  @Test
  void gemini_base_url_path_joined() throws Exception {
    stub.body = fixture("completion-ok.json");
    var base = URI.create(stub.baseUrl() + "/v1beta/openai");

    adapter(base, Duration.ofSeconds(5), false).complete(REQ);

    assertThat(stub.requests().get(0).path()).isEqualTo("/v1beta/openai/chat/completions");
  }

  @Test
  void trailing_slash_base_url_is_normalised() throws Exception {
    stub.body = fixture("completion-ok.json");
    var base = URI.create(stub.baseUrl() + "/v1/");

    adapter(base, Duration.ofSeconds(5), false).complete(REQ);

    assertThat(stub.requests().get(0).path()).isEqualTo("/v1/chat/completions");
  }

  @Test
  void settings_to_string_redacts_key() {
    var s =
        new OpenAiCompatibleSettings(
            "groq", stub.baseUrl(), KEY, "m", 1, false, Duration.ofSeconds(1));

    assertThat(s.toString()).doesNotContain(KEY).contains("groq");
  }
}
