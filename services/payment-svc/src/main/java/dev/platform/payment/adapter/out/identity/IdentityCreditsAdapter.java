package dev.platform.payment.adapter.out.identity;

import dev.platform.payment.application.IdentityCreditsPort;
import dev.platform.payment.application.InsufficientCreditsException;
import dev.platform.payment.application.UpstreamUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * identity-svc over HTTP (connect timeout 1 s, read timeout 3 s). Called outside any database
 * transaction. Only status codes and exception types are logged.
 */
public class IdentityCreditsAdapter implements IdentityCreditsPort {

  private static final Logger LOG = LoggerFactory.getLogger(IdentityCreditsAdapter.class);

  private final RestClient http;
  private final String internalToken;

  public IdentityCreditsAdapter(String baseUrl, String internalToken) {
    this(baseUrl, internalToken, Duration.ofSeconds(1), Duration.ofSeconds(3));
  }

  public IdentityCreditsAdapter(
      String baseUrl, String internalToken, Duration connectTimeout, Duration readTimeout) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(readTimeout);
    this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    this.internalToken = internalToken;
  }

  @Override
  public void reverse(UUID accountId, UUID orderId, int credits) {
    Map<String, Object> body =
        Map.of(
            "account_id", accountId.toString(), "order_id", orderId.toString(), "credits", credits);
    try {
      http.post()
          .uri("/internal/credits/reversals")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(
              h -> {
                if (!internalToken.isEmpty()) {
                  h.set("X-Internal-Token", internalToken);
                }
              })
          .body(body)
          .exchange(
              (req, res) -> {
                int status = res.getStatusCode().value();
                res.getBody().readAllBytes();
                if (res.getStatusCode().is2xxSuccessful()) {
                  return null;
                }
                if (status == 409) {
                  throw new InsufficientCreditsException();
                }
                LOG.warn("identity-svc answered {} to a credit reversal", status);
                throw new UpstreamUnavailableException("identity-svc status");
              });
    } catch (InsufficientCreditsException | UpstreamUnavailableException e) {
      throw e;
    } catch (RestClientException | IllegalStateException e) {
      // I/O failure or connect/read timeout; the type is enough, a message may name the target.
      LOG.warn("identity-svc call failed: {}", e.getClass().getSimpleName());
      throw new UpstreamUnavailableException("identity-svc unreachable");
    }
  }
}
