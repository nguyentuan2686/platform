package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIT {

  static final String TOKEN = "local-test-internal-token";

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("platform.internal-token", () -> TOKEN);
    // This IT runs without a broker: kafkaTopics (covered by KafkaTopicsHealthIndicatorIT) would
    // keep /health/ready DOWN.
    registry.add("management.endpoint.health.group.ready.include", () -> "readinessState,db");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (token != null) {
      request.header("X-Internal-Token", token);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void live_and_ready_are_up_without_token() throws Exception {
    assertThat(get("/health/live", null).statusCode()).isEqualTo(200);
    assertThat(get("/health/ready", null).statusCode()).isEqualTo(200);
  }

  @Test
  void unknown_route_with_token_is_404_problem_json_with_trace_id() throws Exception {
    var res = get("/nope", TOKEN);

    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(res.headers().firstValue("content-type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(res.body()).contains("trace_id");
  }

  @Test
  void unknown_route_without_token_is_401() throws Exception {
    assertThat(get("/nope", null).statusCode()).isEqualTo(401);
  }

  @Test
  void flyway_v1_creates_identity_tables() {
    for (String table : new String[] {"guest", "account", "session", "magic_link", "oidc_login"}) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }
  }
}
