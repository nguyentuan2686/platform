package dev.platform.payment.adapter.out.sepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.platform.payment.application.UpstreamUnavailableException;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SepayApiAdapterTest {

  static final String LIST_URL =
      "https://sepay.test/userapi/transactions/list?account_number=0000000000&limit=100";

  final RestClient.Builder builder = RestClient.builder().baseUrl("https://sepay.test");
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  final SepayApiAdapter adapter =
      new SepayApiAdapter(
          builder.build(),
          "0000000000",
          "local-test-sepay-api-token",
          Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));

  static String fixture() throws IOException {
    return Files.readString(Path.of("src", "test", "resources", "sepay", "transactions-list.json"));
  }

  @Test
  void maps_incoming_transactions_inside_the_lookback() throws IOException {
    server
        .expect(requestTo(LIST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer local-test-sepay-api-token"))
        .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

    List<BankTransaction> result = adapter.recent(Duration.ofHours(24));

    server.verify();
    assertThat(result).extracting(BankTransaction::providerTxId).containsExactly("92704", "92705");
    BankTransaction first = result.get(0);
    assertThat(first.provider()).isEqualTo(Provider.SEPAY);
    assertThat(first.amount()).isEqualTo(Money.of("119000", "VND"));
    assertThat(first.content()).isEqualTo("TV8K2QAB chuyen tien");
    assertThat(first.referenceCode()).isEqualTo("FT26329000001");
    // 2026-11-25 16:30:05 in Asia/Ho_Chi_Minh (UTC+7)
    assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-11-25T09:30:05Z"));
  }

  @Test
  void upstream_error_is_an_upstream_unavailable_failure() {
    server.expect(requestTo(LIST_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> adapter.recent(Duration.ofHours(24)))
        .isInstanceOf(UpstreamUnavailableException.class)
        .hasMessageNotContaining("0000000000");
  }

  @Test
  void an_unusable_body_is_an_upstream_unavailable_failure() {
    server
        .expect(requestTo(LIST_URL))
        .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> adapter.recent(Duration.ofHours(24)))
        .isInstanceOf(UpstreamUnavailableException.class);
  }
}
