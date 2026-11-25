package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import java.util.Map;

/** PayPal capture webhooks: verified by the port, matched on the PayPal order id. */
public class PayPalWebhookUseCase {

  private final PayPalPort paypal;
  private final RecordBankTransactionUseCase recorder;

  public PayPalWebhookUseCase(PayPalPort paypal, RecordBankTransactionUseCase recorder) {
    this.paypal = paypal;
    this.recorder = recorder;
  }

  public boolean verify(Map<String, String> headers, byte[] body) {
    return paypal.verifyWebhook(headers, body);
  }

  /** The amount must equal the order's: both lower and higher are left for a human. */
  public MatchOutcome captureCompleted(PayPalCapture capture) {
    BankTransaction tx =
        new BankTransaction(
            Provider.PAYPAL,
            capture.captureId(),
            capture.amount(),
            null,
            capture.paypalOrderId(),
            capture.occurredAt());
    return recorder.settle(
        tx, ReceivedVia.WEBHOOK, t -> recorder.byProviderRef(capture.paypalOrderId()), true);
  }
}
