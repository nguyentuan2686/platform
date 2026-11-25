package dev.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * A full webhook plus admin run must not print the payer's transfer text, the account number or any
 * secret (plan review focus 5, spec 7).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"platform.outbox.poll-interval=200ms", "platform.outbox.send-timeout=2s"})
@ExtendWith(OutputCaptureExtension.class)
class NoPiiInLogsIT extends PaymentIntegrationSupport {

  static final String PAYER_TEXT = "GHIRIENG Nguyen Van Bimat";
  static final String ACCOUNT_NUMBER = "0000000000";

  @Test
  void webhook_and_admin_run_prints_neither_transfer_content_nor_account_number_nor_secrets(
      CapturedOutput output) throws Exception {
    UUID account = UUID.randomUUID();
    JsonNode order = createOrder(account, "CREDIT_1");
    String code = order.get("code").asString();
    String matched = uniqueTxId();
    String unmatched = uniqueTxId();
    String underpaid = uniqueTxId();

    // matched, duplicate, no code, underpaid, bad credentials, malformed body
    sepayWebhook(sepayBody(matched, code + " " + PAYER_TEXT, "29000"));
    sepayWebhook(sepayBody(matched, code + " " + PAYER_TEXT, "29000"));
    sepayWebhook(sepayBody(unmatched, PAYER_TEXT + " khong ma", "29000"));
    sepayWebhook(sepayBody(underpaid, "TV2222AB " + PAYER_TEXT, "1000"));
    send(
        "POST",
        "/webhooks/sepay",
        sepayBody(uniqueTxId(), PAYER_TEXT, "29000"),
        Map.of("Authorization", "Apikey wrong-" + PAYER_TEXT));
    send(
        "POST",
        "/webhooks/sepay",
        "{" + PAYER_TEXT,
        Map.of("Authorization", "Apikey " + SEPAY_SECRET));

    HttpResponse<String> queue = admin("GET", "/admin/unmatched-transactions", null);
    assertThat(queue.statusCode()).isEqualTo(200);
    assertThat(queue.body()).contains(PAYER_TEXT); // the operator does see it
    long id = JSON.readTree(queue.body()).get(0).get("id").asLong();
    admin(
        "POST",
        "/admin/unmatched-transactions/" + id + "/resolve",
        "{\"resolution\":\"IGNORED\",\"note\":\"" + PAYER_TEXT + "\"}");
    send("GET", "/admin/unmatched-transactions", null, Map.of("X-Admin-Token", "wrong-token"));

    String logs = output.getAll();
    assertThat(logs).contains("provider transaction SEPAY " + matched); // it did log something
    assertThat(logs)
        .doesNotContain(PAYER_TEXT)
        .doesNotContain("GHIRIENG")
        .doesNotContain(ACCOUNT_NUMBER)
        .doesNotContain(SEPAY_SECRET)
        .doesNotContain(ADMIN)
        .doesNotContain(TOKEN)
        .doesNotContain("wrong-token");
  }
}
