package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.platform.reading.tuvi.adapter.out.chart.ChartMapper;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.DailyResult;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** End to end: seed knowledge imported at startup, real Postgres, chart-svc replaced by a stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadingFlowIT {

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
  }

  static final JsonMapper JSON = JsonMapper.shared();
  static final String BODY =
      "{\"birth\":\"1994-03-12T08:30\",\"zone\":\"Asia/Ho_Chi_Minh\",\"gender\":\"male\","
          + "\"calendar\":\"VN\"%s}";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ChartPort charts;

  @BeforeEach
  void stubChartSvc() throws IOException {
    JsonNode chart = fixture("/chart-fixture.json");
    JsonNode daily = fixture("/daily-fixture.json");
    when(charts.chart(any(Birth.class)))
        .thenReturn(
            new ChartResult(ChartMapper.chart(chart), "f".repeat(64), ChartMapper.school(chart)));
    when(charts.daily(any(Birth.class), any(LocalDate.class)))
        .thenReturn(
            new DailyResult(
                ChartMapper.daily(daily), ChartMapper.lunar(daily), ChartMapper.dayCanChi(daily)));
  }

  static JsonNode fixture(String name) throws IOException {
    try (InputStream in = ReadingFlowIT.class.getResourceAsStream(name)) {
      return JSON.readTree(in);
    }
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void basic_reading_from_the_seed_has_17_sections_and_is_cached() throws Exception {
    HttpResponse<String> res = post("/tuvi/readings/basic", BODY.formatted(""));

    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode json = JSON.readTree(res.body());
    assertThat(json.get("knowledgeVersion").asInt()).isEqualTo(1);
    assertThat(json.get("sections")).hasSize(17);
    assertThat(json.get("sections").get(16).get("id").asString()).isEqualTo("disclaimer");
    assertThat(json.get("sections").get(0).get("lines").get(0).asString())
        .startsWith("Con đến rồi");
    assertThat(json.get("sections").get(1).get("title").asString())
        .isEqualTo("Cung Mệnh, gốc của lá số");
    json.get("sections")
        .forEach(
            s -> s.get("lines").forEach(l -> assertThat(l.asString()).doesNotContain("{", "}")));
    // palace sections share one title frame, rendered with the palace name
    assertThat(json.get("sections").get(4).get("title").asString()).isEqualTo("Cung Phụ Mẫu");

    assertThat(jdbc.queryForObject("select count(*) from basic_reading_cache", Long.class))
        .isEqualTo(1);
    HttpResponse<String> again = post("/tuvi/readings/basic", BODY.formatted(""));
    assertThat(JSON.readTree(again.body())).isEqualTo(json);
    assertThat(jdbc.queryForObject("select count(*) from basic_reading_cache", Long.class))
        .isEqualTo(1);
  }

  @Test
  void vn_chart_school_reads_the_shared_iztro_default_corpus() throws Exception {
    assertThat(ChartMapper.school(fixture("/chart-fixture.json"))).startsWith("vn+");

    JsonNode json = JSON.readTree(post("/tuvi/readings/basic", BODY.formatted("")).body());

    String missing =
        jdbc.queryForObject(
            "select content from knowledge where key = 'tuvi:frame=missing'"
                + " and school = 'iztro-default' and lang = 'vi' and status = 'approved'",
            String.class);
    long found = 0;
    for (JsonNode section : json.get("sections")) {
      for (JsonNode line : section.get("lines")) {
        if (!line.asString().equals(missing)) {
          found++;
        }
      }
    }
    assertThat(found).isPositive();
  }

  @Test
  void today_reading_has_four_areas_from_the_seed() throws Exception {
    HttpResponse<String> res =
        post("/tuvi/readings/today", BODY.formatted(",\"date\":\"2026-11-24\""));

    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode json = JSON.readTree(res.body());
    assertThat(json.get("areas")).hasSize(4);
    assertThat(json.get("areas").get(0).get("area").asString()).isEqualTo("CongViec");
    assertThat(json.get("opening").asString()).contains("24/11/2026").doesNotContain("{");
    assertThat(res.body()).doesNotContain("tuvi:frame");
    json.get("areas")
        .forEach(a -> assertThat(a.get("line").asString()).doesNotStartWith("Điều này ta chưa"));
  }

  @Test
  void upstream_outage_is_a_503_problem() throws Exception {
    when(charts.chart(any(Birth.class))).thenThrow(new UpstreamUnavailableException("x"));

    HttpResponse<String> res = post("/tuvi/readings/basic", BODY.formatted(""));

    assertThat(res.statusCode()).isEqualTo(503);
    assertThat(res.headers().firstValue("content-type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(JSON.readTree(res.body()).get("type").asString())
        .isEqualTo("urn:problem:upstream-unavailable");
  }
}
