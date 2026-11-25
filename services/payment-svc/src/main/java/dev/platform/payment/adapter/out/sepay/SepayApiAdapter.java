package dev.platform.payment.adapter.out.sepay;

import dev.platform.payment.application.BankTransactionsPort;
import dev.platform.payment.application.UpstreamUnavailableException;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SePay's transaction list over HTTP, for reconciliation. Request and response shape follow the
 * public SePay API documentation (https://docs.sepay.vn/ , "Danh sach giao dich": GET
 * /userapi/transactions/list, Bearer API token, fields id, transaction_date (Vietnam local time),
 * amount_in, amount_out, transaction_content, reference_number). Logs never contain the account
 * number, the transfer content or the token; only status codes and exception types.
 */
public class SepayApiAdapter implements BankTransactionsPort {

  private static final Logger LOG = LoggerFactory.getLogger(SepayApiAdapter.class);
  private static final JsonMapper JSON = JsonMapper.shared();
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final RestClient http;
  private final String accountNumber;
  private final String apiToken;
  private final Clock clock;

  public SepayApiAdapter(RestClient http, String accountNumber, String apiToken, Clock clock) {
    this.http = http;
    this.accountNumber = accountNumber;
    this.apiToken = apiToken;
    this.clock = clock;
  }

  /** A client with a 1 s connect and 5 s read timeout for the given API base URL. */
  public static RestClient client(String baseUrl) {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  @Override
  public List<BankTransaction> recent(Duration lookback) {
    byte[] body;
    try {
      body =
          http.get()
              .uri("/userapi/transactions/list?account_number={acc}&limit=100", accountNumber)
              .header("Authorization", "Bearer " + apiToken)
              .retrieve()
              .body(byte[].class);
    } catch (RestClientException | IllegalStateException e) {
      LOG.warn("sepay api call failed: {}", e.getClass().getSimpleName());
      throw new UpstreamUnavailableException("sepay unavailable");
    }
    try {
      return parse(JSON.readTree(body), clock.instant().minus(lookback));
    } catch (RuntimeException e) {
      LOG.warn("sepay api answered an unusable body: {}", e.getClass().getSimpleName());
      throw new UpstreamUnavailableException("sepay body");
    }
  }

  private static List<BankTransaction> parse(JsonNode root, Instant since) {
    JsonNode list = root.path("transactions");
    if (!list.isArray()) {
      throw new IllegalArgumentException("no transactions array");
    }
    List<BankTransaction> result = new ArrayList<>();
    for (JsonNode t : list) {
      BigDecimal in = new BigDecimal(t.path("amount_in").asString("0"));
      if (in.signum() <= 0) {
        continue; // outgoing money is not a payment
      }
      Instant at =
          LocalDateTime.parse(t.path("transaction_date").asString(), DATE_TIME)
              .atZone(VIETNAM)
              .toInstant();
      if (at.isBefore(since)) {
        continue;
      }
      JsonNode content = t.path("transaction_content");
      JsonNode reference = t.path("reference_number");
      result.add(
          new BankTransaction(
              Provider.SEPAY,
              t.path("id").asString(),
              new Money(in, "VND"),
              content.isNull() || content.isMissingNode() ? null : content.asString(),
              reference.isNull() || reference.isMissingNode() ? null : reference.asString(),
              at));
    }
    return result;
  }
}
