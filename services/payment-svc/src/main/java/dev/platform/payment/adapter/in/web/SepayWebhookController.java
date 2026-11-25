package dev.platform.payment.adapter.in.web;

import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.RecordBankTransactionUseCase;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SePay's bank webhook. Open of the internal token (SePay cannot send it) and authenticated by
 * {@link SepayWebhookAuthenticator}; a 401 carries no detail. Once authenticated and parseable the
 * answer is always 200 {"success": true}, whatever the match outcome, so SePay does not retry what
 * a human has to resolve. The body holds the payer's text and account number: never logged.
 */
@RestController
public class SepayWebhookController {

  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final java.time.format.DateTimeFormatter DATE_TIME =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final SepayWebhookAuthenticator authenticator;
  private final RecordBankTransactionUseCase recorder;
  private final JsonMapper json;

  public SepayWebhookController(
      SepayWebhookAuthenticator authenticator,
      RecordBankTransactionUseCase recorder,
      JsonMapper json) {
    this.authenticator = authenticator;
    this.recorder = recorder;
    this.json = json;
  }

  @PostMapping("/webhooks/sepay")
  Map<String, Boolean> receive(
      @RequestBody(required = false) byte[] raw,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = SepayWebhookAuthenticator.SIGNATURE_HEADER, required = false)
          String signature) {
    byte[] body = raw == null ? new byte[0] : raw;
    if (!authenticator.authenticate(authorization, signature, body)) {
      throw new UnauthenticatedException();
    }
    BankTransaction tx = parse(body);
    if (tx != null) {
      recorder.record(tx, ReceivedVia.WEBHOOK);
    }
    return Map.of("success", true);
  }

  /** Null for money going out, which is none of our business. */
  private BankTransaction parse(byte[] body) {
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      throw malformed("body");
    }
    if (root == null || !root.isObject()) {
      throw malformed("body");
    }
    if (!"in".equalsIgnoreCase(root.path("transferType").asString(""))) {
      return null;
    }
    String id = root.path("id").asString("");
    if (id.isBlank()) {
      throw malformed("id");
    }
    Money amount = amount(root.path("transferAmount"));
    return new BankTransaction(
        Provider.SEPAY,
        id,
        amount,
        text(root.path("content")),
        text(root.path("referenceCode")),
        date(root.path("transactionDate")));
  }

  private static Money amount(JsonNode node) {
    try {
      BigDecimal value = new BigDecimal(node.asString(""));
      if (value.signum() <= 0) {
        throw malformed("transferAmount");
      }
      return new Money(value, "VND");
    } catch (NumberFormatException | ArithmeticException e) {
      throw malformed("transferAmount");
    }
  }

  private static java.time.Instant date(JsonNode node) {
    try {
      return LocalDateTime.parse(node.asString(""), DATE_TIME).atZone(VIETNAM).toInstant();
    } catch (DateTimeParseException e) {
      throw malformed("transactionDate");
    }
  }

  private static String text(JsonNode node) {
    return node.isNull() || node.isMissingNode() ? null : node.asString();
  }

  private static OrderValidationException malformed(String field) {
    return new OrderValidationException(field, "Malformed webhook body");
  }
}
