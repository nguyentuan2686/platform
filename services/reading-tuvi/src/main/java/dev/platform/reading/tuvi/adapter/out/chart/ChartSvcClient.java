package dev.platform.reading.tuvi.adapter.out.chart;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.DailyResult;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * chart-svc over HTTP (connect timeout 1 s, read timeout 3 s). Logs never contain the request or
 * response body (they hold birth data); only status codes and exception types are logged.
 */
public class ChartSvcClient implements ChartPort {

  private static final Logger LOG = LoggerFactory.getLogger(ChartSvcClient.class);
  private static final JsonMapper JSON = JsonMapper.shared();

  private final RestClient http;

  public ChartSvcClient(String baseUrl) {
    this(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3));
  }

  public ChartSvcClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(readTimeout);
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  @Override
  public ChartResult chart(Birth birth) {
    byte[] body = post("/tuvi/chart", request(birth, null));
    try {
      JsonNode root = JSON.readTree(body);
      return new ChartResult(ChartMapper.chart(root), sha256(body), ChartMapper.school(root));
    } catch (RuntimeException e) {
      throw unusable(e);
    }
  }

  @Override
  public DailyResult daily(Birth birth, LocalDate date) {
    byte[] body = post("/tuvi/daily", request(birth, date));
    try {
      JsonNode root = JSON.readTree(body);
      return new DailyResult(
          ChartMapper.daily(root), ChartMapper.lunar(root), ChartMapper.dayCanChi(root));
    } catch (RuntimeException e) {
      throw unusable(e);
    }
  }

  private static ObjectNode request(Birth b, LocalDate date) {
    ObjectNode n = JSON.createObjectNode();
    put(n, "birth", b.birth());
    put(n, "zone", b.zone());
    put(n, "gender", b.gender());
    put(n, "calendar", b.calendar());
    if (date != null) {
      n.put("date", date.toString());
    }
    return n;
  }

  private static void put(ObjectNode n, String name, String value) {
    if (value != null) {
      n.put(name, value);
    }
  }

  private byte[] post(String path, ObjectNode request) {
    try {
      return http.post()
          .uri(path)
          .contentType(MediaType.APPLICATION_JSON)
          .body(request.toString())
          .exchange(
              (req, res) -> {
                int status = res.getStatusCode().value();
                byte[] bytes = res.getBody().readAllBytes();
                if (res.getStatusCode().is2xxSuccessful()) {
                  return bytes;
                }
                if (status == 400) {
                  throw rejected(bytes);
                }
                LOG.warn("chart-svc answered {}", status);
                throw new UpstreamUnavailableException("chart-svc status");
              });
    } catch (UpstreamValidationException | UpstreamUnavailableException e) {
      throw e;
    } catch (RestClientException | IllegalStateException e) {
      // I/O failure or connect/read timeout; the type is enough, a message may name the target.
      LOG.warn("chart-svc call failed: {}", e.getClass().getSimpleName());
      throw new UpstreamUnavailableException("chart-svc unreachable");
    }
  }

  private static RuntimeException rejected(byte[] bytes) {
    try {
      List<UpstreamValidationException.FieldError> errors = new ArrayList<>();
      for (JsonNode e : JSON.readTree(bytes).path("errors")) {
        errors.add(
            new UpstreamValidationException.FieldError(
                e.path("field").asString(), e.path("message").asString()));
      }
      if (errors.isEmpty()) {
        errors.add(new UpstreamValidationException.FieldError("body", "Invalid request"));
      }
      return new UpstreamValidationException(errors);
    } catch (RuntimeException e) {
      return unusable(e);
    }
  }

  private static UpstreamUnavailableException unusable(RuntimeException e) {
    LOG.warn("chart-svc answered an unusable body: {}", e.getClass().getSimpleName());
    return new UpstreamUnavailableException("chart-svc body");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
