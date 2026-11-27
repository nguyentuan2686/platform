package dev.platform.__JAVA_PKG__;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The whole application on a random port: probes, the sample endpoint and the error contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIT {

  @LocalServerPort int port;

  private HttpResponse<String> get(String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void live_and_ready_are_up() throws Exception {
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
  }

  @Test
  void info_names_the_service() throws Exception {
    var res = get("/v1/info");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"service\":\"__SERVICE__\"");
  }

  @Test
  void unknown_route_is_404_problem_json_with_trace_id() throws Exception {
    var res = get("/nope");

    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(res.headers().firstValue("content-type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(res.body()).contains("trace_id");
  }
}
