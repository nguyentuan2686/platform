package dev.platform.reading.tuvi;

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

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Internal-Token", "local-test-internal-token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void live_and_ready_are_up() throws Exception {
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
  }

  @Test
  void unknown_route_is_404_problem_json_with_trace_id() throws Exception {
    var res = get("/nope");

    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(res.headers().firstValue("content-type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(res.body()).contains("trace_id");
  }

  @Test
  void flyway_v1_creates_knowledge_tables_and_seeds_meta() {
    assertThat(jdbc.queryForObject("select version from knowledge_meta where id = 1", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from knowledge", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from basic_reading_cache", Long.class))
        .isZero();
  }
}
