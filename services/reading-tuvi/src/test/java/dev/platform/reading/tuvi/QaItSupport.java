package dev.platform.reading.tuvi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.platform.reading.tuvi.adapter.out.chart.ChartMapper;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.DailyResult;
import dev.platform.reading.tuvi.application.qa.ConsumeOutcome;
import dev.platform.reading.tuvi.application.qa.IdentityPort;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.application.qa.RefundOutcome;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup of the Q&A ITs (one Spring context): real Postgres and knowledge import, the real
 * LLM gateway with the {@code fake} provider, chart-svc and identity-svc replaced by mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class QaItSupport {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("knowledge.dir", () -> "../../knowledge/tuvi");
    registry.add("LLM_PROVIDERS", () -> "fake");
  }

  static final JsonMapper JSON = JsonMapper.shared();
  static final String TOKEN = "local-test-internal-token";
  static final String ACCOUNT_BEARER = "local-test-session-account-0123456789abcdef";
  static final String GUEST_BEARER = "local-test-session-guest-0123456789abcdef";
  static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  static final UUID GUEST = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  static final String BIRTH = "1994-03-12T08:30";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ChartPort charts;
  @MockitoBean IdentityPort identity;

  @BeforeEach
  void stubs() throws Exception {
    jdbc.update("delete from qa_reading");
    jdbc.update("delete from qa_answer_cache");
    JsonNode chart = fixture("/chart-fixture.json");
    JsonNode daily = fixture("/daily-fixture.json");
    when(charts.chart(any(Birth.class)))
        .thenReturn(
            new ChartResult(ChartMapper.chart(chart), "f".repeat(64), ChartMapper.school(chart)));
    when(charts.daily(any(Birth.class), any(LocalDate.class)))
        .thenReturn(
            new DailyResult(
                ChartMapper.daily(daily), ChartMapper.lunar(daily), ChartMapper.dayCanChi(daily)));
    when(identity.whoAmI(ACCOUNT_BEARER)).thenReturn(new IdentitySubject("account", ACCOUNT));
    when(identity.whoAmI(GUEST_BEARER)).thenReturn(new IdentitySubject("guest", GUEST));
    when(identity.profile(anyString()))
        .thenReturn(Optional.of(new Birth(BIRTH, "Asia/Ho_Chi_Minh", "male", "VN")));
    when(identity.consume(any(), any(), any())).thenReturn(new ConsumeOutcome.Charged("CREDIT"));
    when(identity.refund(any())).thenReturn(RefundOutcome.REFUNDED);
  }

  static JsonNode fixture(String name) throws Exception {
    try (InputStream in = QaItSupport.class.getResourceAsStream(name)) {
      return JSON.readTree(in);
    }
  }

  record SseEvent(String name, JsonNode data) {}

  static String ask(UUID id, String kind, String topic, String question) {
    return JSON.createObjectNode()
        .put("reading_id", id.toString())
        .put("kind", kind)
        .put("topic", topic)
        .put("question", question)
        .put("lang", "vi")
        .toString();
  }

  HttpResponse<String> send(String method, String path, String body, String bearer)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Internal-Token", TOKEN)
            .header("Authorization", "Bearer " + bearer)
            .header("Accept", "text/event-stream, application/json");
    if (body != null) {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return HttpClient.newHttpClient()
        .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  JsonNode getJson(String path, String bearer) throws Exception {
    return JSON.readTree(send("GET", path, null, bearer).body());
  }

  static List<SseEvent> events(String body) {
    List<SseEvent> out = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String name = null;
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring(6).strip();
        } else if (line.startsWith("data:")) {
          data.append(line.substring(5));
        }
      }
      if (name != null) {
        out.add(new SseEvent(name, JSON.readTree(data.toString())));
      }
    }
    return out;
  }
}
