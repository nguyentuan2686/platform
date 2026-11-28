package dev.platform.reading.tuvi.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmRateLimitedException;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmTier;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import dev.platform.reading.tuvi.support.LlmStubServer;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Anthropic Messages API adapter against a local stub; never calls the real API. */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicLlmAdapterTest {

  static final String KEY = "local-test-anthropic-key";
  static final String SYSTEM = "Hãy trả lời với tư cách Lão Thiên Cơ";
  static final String QUESTION = "CÂU HỎI bí mật về đường tình duyên";
  static final LlmRequest REQ =
      new LlmRequest(
          List.of(
              new LlmMessage(LlmMessage.Role.SYSTEM, SYSTEM),
              new LlmMessage(LlmMessage.Role.USER, "câu cũ"),
              new LlmMessage(LlmMessage.Role.ASSISTANT, "đáp cũ"),
              new LlmMessage(LlmMessage.Role.USER, QUESTION)),
          321,
          0.6,
          LlmTier.ANY);

  static final String OK =
      """
      {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
       "content":[{"type":"thinking","thinking":"","signature":"sig"},
                  {"type":"text","text":"Con à, "},
                  {"type":"text","text":"ta thấy cung Phu Thê sáng."}],
       "stop_reason":"end_turn",
       "usage":{"input_tokens":100,"cache_creation_input_tokens":50,
                "cache_read_input_tokens":30,"output_tokens":40}}
      """;

  LlmStubServer stub;

  @BeforeEach
  void start() {
    stub = new LlmStubServer();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  static AnthropicSettings settings(URI base, Duration timeout, boolean cache, boolean free) {
    return new AnthropicSettings(
        "claude", base, KEY, "claude-haiku-5-5", "low", 100, cache, 1000, free, timeout);
  }

  AnthropicLlmAdapter adapter(URI base, Duration timeout) {
    return new AnthropicLlmAdapter(settings(base, timeout, true, true), RestClient.builder());
  }

  AnthropicLlmAdapter adapter() {
    return adapter(stub.baseUrl(), Duration.ofSeconds(5));
  }

  JsonNode sent() {
    return JsonMapper.shared().readTree(stub.requests().get(0).body());
  }

  @Test
  void sends_messages_api_shape() {
    stub.body = OK;

    adapter().complete(REQ);

    var rec = stub.requests().get(0);
    assertThat(rec.method()).isEqualTo("POST");
    assertThat(rec.path()).isEqualTo("/v1/messages");
    assertThat(rec.headers().get("x-api-key")).isEqualTo(KEY);
    assertThat(rec.headers().get("anthropic-version")).isEqualTo("2026-10-21");
    assertThat(rec.headers()).doesNotContainKey("authorization");
    JsonNode json = sent();
    assertThat(json.get("model").asString()).isEqualTo("claude-haiku-5-5");
    // answer budget plus room for adaptive thinking, which counts toward max_tokens
    assertThat(json.get("max_tokens").asInt()).isEqualTo(421);
    assertThat(json.get("output_config").get("effort").asString()).isEqualTo("low");
    assertThat(json.has("temperature")).isFalse();
    assertThat(json.has("thinking")).isFalse();
    assertThat(json.has("stream")).isFalse();
    JsonNode system = json.get("system");
    assertThat(system).hasSize(1);
    assertThat(system.get(0).get("type").asString()).isEqualTo("text");
    assertThat(system.get(0).get("text").asString()).isEqualTo(SYSTEM);
    assertThat(system.get(0).get("cache_control").get("type").asString()).isEqualTo("ephemeral");
    JsonNode messages = json.get("messages");
    assertThat(messages).hasSize(3);
    assertThat(messages.get(0).get("role").asString()).isEqualTo("user");
    assertThat(messages.get(1).get("role").asString()).isEqualTo("assistant");
    assertThat(messages.get(1).get("content").asString()).isEqualTo("đáp cũ");
    assertThat(messages.get(2).get("content").asString()).isEqualTo(QUESTION);
  }

  @Test
  void prompt_cache_off_sends_no_cache_control() {
    stub.body = OK;
    var a =
        new AnthropicLlmAdapter(
            settings(stub.baseUrl(), Duration.ofSeconds(5), false, true), RestClient.builder());

    a.complete(REQ);

    assertThat(sent().get("system").get(0).has("cache_control")).isFalse();
  }

  @Test
  void no_system_message_sends_no_system() {
    stub.body = OK;
    var req =
        new LlmRequest(List.of(new LlmMessage(LlmMessage.Role.USER, "hỏi")), 50, 0.6, LlmTier.ANY);

    adapter().complete(req);

    assertThat(sent().has("system")).isFalse();
  }

  @Test
  void parses_text_blocks_and_cache_usage() {
    stub.body = OK;

    var out = adapter().complete(REQ);

    assertThat(out.text()).isEqualTo("Con à, ta thấy cung Phu Thê sáng.");
    assertThat(out.provider()).isEqualTo("claude");
    assertThat(out.promptTokens()).isEqualTo(180);
    assertThat(out.completionTokens()).isEqualTo(40);
    assertThat(out.cacheWriteTokens()).isEqualTo(50);
    assertThat(out.cacheReadTokens()).isEqualTo(30);
  }

  @Test
  void flags_come_from_settings() {
    var a = adapter();

    assertThat(a.id()).isEqualTo("claude");
    assertThat(a.paid()).isTrue();
    assertThat(a.servesFreeTier()).isTrue();
    assertThat(a.dailyRequestLimit()).isEqualTo(1000);
    var paidOnly =
        new AnthropicLlmAdapter(
            settings(stub.baseUrl(), Duration.ofSeconds(1), true, false), RestClient.builder());
    assertThat(paidOnly.servesFreeTier()).isFalse();
  }

  @Test
  void rate_limited_with_retry_after() {
    stub.status = 429;
    stub.responseHeaders = Map.of("Retry-After", "15");
    stub.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}";

    assertThatThrownBy(() -> adapter().complete(REQ))
        .isInstanceOfSatisfying(
            LlmRateLimitedException.class,
            e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(15)));
  }

  @Test
  void rate_limited_without_header() {
    stub.status = 429;

    assertThatThrownBy(() -> adapter().complete(REQ))
        .isInstanceOfSatisfying(
            LlmRateLimitedException.class, e -> assertThat(e.retryAfter()).isEmpty());
  }

  @Test
  void overloaded_529_is_unavailable() {
    stub.status = 529;
    stub.body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}";

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void server_error_is_unavailable() {
    stub.status = 500;

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void timeout_is_unavailable() {
    stub.delayMillis = 1500;
    stub.body = OK;
    var slow = adapter(stub.baseUrl(), Duration.ofMillis(200));

    assertThatThrownBy(() -> slow.complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void connection_refused_is_unavailable() {
    URI dead = stub.baseUrl();
    stub.close();

    assertThatThrownBy(() -> adapter(dead, Duration.ofSeconds(1)).complete(REQ))
        .isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void refusal_is_unavailable_so_the_next_provider_answers() {
    stub.body =
        """
        {"content":[{"type":"text","text":"partial"}],"stop_reason":"refusal",
         "stop_details":{"type":"refusal","category":"general_harms"},
         "usage":{"input_tokens":10,"output_tokens":1}}
        """;

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void thinking_only_reply_is_unavailable() {
    stub.body =
        """
        {"content":[{"type":"thinking","thinking":"","signature":"s"}],
         "stop_reason":"max_tokens","usage":{"input_tokens":10,"output_tokens":421}}
        """;

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void malformed_json_is_unavailable() {
    stub.body = "not json";

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void client_error_logs_status_not_key(CapturedOutput output) {
    stub.status = 401;
    stub.body = "{\"error\":\"bad key " + KEY + "\"}";

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);

    assertThat(output.getAll())
        .contains("llm provider rejected request")
        .contains("provider=claude")
        .contains("status=401")
        .doesNotContain(KEY);
  }

  @Test
  void billing_error_402_is_unavailable() {
    stub.status = 402;

    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  void never_logs_prompt_question_or_answer(CapturedOutput output) {
    stub.body = OK;
    adapter().complete(REQ);
    stub.status = 400;
    assertThatThrownBy(() -> adapter().complete(REQ)).isInstanceOf(LlmUnavailableException.class);

    assertThat(output.getAll())
        .doesNotContain(SYSTEM)
        .doesNotContain(QUESTION)
        .doesNotContain("Phu Thê")
        .doesNotContain(KEY);
  }

  @Test
  void trailing_slash_base_url_is_normalised() {
    stub.body = OK;

    adapter(URI.create(stub.baseUrl() + "/"), Duration.ofSeconds(5)).complete(REQ);

    assertThat(stub.requests().get(0).path()).isEqualTo("/v1/messages");
  }

  @Test
  void key_with_trailing_newline_is_stripped() {
    stub.body = OK;
    var a =
        new AnthropicLlmAdapter(
            new AnthropicSettings(
                "claude",
                stub.baseUrl(),
                KEY + "\n",
                "m",
                "low",
                0,
                true,
                1,
                true,
                Duration.ofSeconds(5)),
            RestClient.builder());

    a.complete(REQ);

    assertThat(stub.requests().get(0).headers().get("x-api-key")).isEqualTo(KEY);
  }

  @Test
  void settings_to_string_redacts_key() {
    assertThat(settings(stub.baseUrl(), Duration.ofSeconds(1), true, true).toString())
        .doesNotContain(KEY)
        .contains("claude-haiku-5-5");
  }
}
