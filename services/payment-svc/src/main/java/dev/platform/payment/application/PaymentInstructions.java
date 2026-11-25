package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import java.net.URI;

/** How the customer pays a freshly created order. */
public sealed interface PaymentInstructions {

  /** Bank transfer; {@code content} is the order code the customer must type. */
  record VietQr(
      String bankCode,
      String accountNumber,
      String accountName,
      Money amount,
      String content,
      String qrImageUrl)
      implements PaymentInstructions {

    @Override
    public String toString() {
      return "VietQr[redacted]";
    }
  }

  record PayPal(URI approveUrl) implements PaymentInstructions {}
}
