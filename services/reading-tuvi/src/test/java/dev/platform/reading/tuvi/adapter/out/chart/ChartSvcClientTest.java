package dev.platform.reading.tuvi.adapter.out.chart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.DailyResult;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real client against a tiny in-process HTTP server (real sockets, real timeouts). */
class ChartSvcClientTest {

  static final Birth BIRTH = new Birth("1994-03-12T08:30", null, "male", null);

  HttpServer server;
  ChartSvcClient client;
  final AtomicReference<String> lastRequest = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
    client = new ChartSvcClient(baseUrl());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void respond(String path, int status, byte[] body, long delayMillis) {
    server.createContext(
        path,
        exchange -> {
          lastRequest.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            Thread.sleep(delayMillis);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          } catch (InterruptedException | IOException e) {
            // client gave up
          } finally {
            exchange.close();
          }
        });
  }

  static byte[] resource(String name) throws IOException {
    try (InputStream in = ChartSvcClientTest.class.getResourceAsStream(name)) {
      return in.readAllBytes();
    }
  }

  @Test
  void chart_maps_the_response_and_hashes_the_exact_body_bytes() throws Exception {
    byte[] body = resource("/chart-fixture.json");
    respond("/tuvi/chart", 200, body, 0);

    ChartResult r = client.chart(BIRTH);

    assertThat(r.chart().palaces()).hasSize(12);
    assertThat(r.chart().lunarBirthYear()).isEqualTo(1994);
    assertThat(r.chart().palace("Menh").decade())
        .isEqualTo(new dev.platform.reading.tuvi.domain.Palace.Decade(6, 15));
    assertThat(r.chartHash())
        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)))
        .matches("[0-9a-f]{64}");
    assertThat(r.school())
        .isEqualTo("vn+vn-calendar;hour=local-standard;ty23=next-day;leap=split15");
    assertThat(lastRequest.get()).contains("\"birth\":\"1994-03-12T08:30\"", "\"gender\":\"male\"");
    assertThat(lastRequest.get()).doesNotContain("zone", "calendar", "date");
  }

  @Test
  void daily_maps_flow_layers_lunar_and_day_can_chi() throws Exception {
    respond("/tuvi/daily", 200, resource("/daily-fixture.json"), 0);

    DailyResult r = client.daily(BIRTH, LocalDate.of(2026, 11, 24));

    assertThat(r.facts().daily().mutagens()).hasSize(4);
    assertThat(r.lunar()).matches("\\d{1,2}/\\d{1,2}( nhuận)?");
    assertThat(r.dayCanChi()).isNotBlank();
    assertThat(lastRequest.get()).contains("\"date\":\"2026-11-24\"");
  }

  @Test
  void a_400_is_passed_through_with_field_names() {
    String problem =
        "{\"type\":\"urn:problem:validation-error\",\"title\":\"Bad Request\",\"status\":400,"
            + "\"errors\":[{\"field\":\"birth\",\"message\":\"must be a date\"},"
            + "{\"field\":\"gender\",\"message\":\"must be male or female\"}],\"trace_id\":\"\"}";
    respond("/tuvi/chart", 400, problem.getBytes(StandardCharsets.UTF_8), 0);

    assertThatThrownBy(() -> client.chart(BIRTH))
        .isInstanceOfSatisfying(
            UpstreamValidationException.class,
            e ->
                assertThat(e.errors())
                    .extracting(
                        UpstreamValidationException.FieldError::field,
                        UpstreamValidationException.FieldError::message)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("birth", "must be a date"),
                        org.assertj.core.groups.Tuple.tuple("gender", "must be male or female")));
  }

  @Test
  void a_500_is_upstream_unavailable() {
    respond("/tuvi/chart", 500, "{}".getBytes(StandardCharsets.UTF_8), 0);

    assertThatThrownBy(() -> client.chart(BIRTH)).isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void an_unparseable_200_is_upstream_unavailable() {
    respond("/tuvi/chart", 200, "not json".getBytes(StandardCharsets.UTF_8), 0);

    assertThatThrownBy(() -> client.chart(BIRTH)).isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void connection_refused_is_upstream_unavailable() {
    server.stop(0);
    ChartSvcClient dead = new ChartSvcClient(baseUrl());

    assertThatThrownBy(() -> dead.chart(BIRTH)).isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void a_response_slower_than_the_three_second_read_timeout_is_upstream_unavailable() {
    respond("/tuvi/chart", 200, "{}".getBytes(StandardCharsets.UTF_8), 4000);

    long start = System.nanoTime();
    assertThatThrownBy(() -> client.chart(BIRTH)).isInstanceOf(UpstreamUnavailableException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3900));
  }
}
