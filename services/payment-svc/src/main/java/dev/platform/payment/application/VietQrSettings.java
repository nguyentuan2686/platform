package dev.platform.payment.application;

/**
 * The receiving bank account and the QR image URL template ({acc}, {bank}, {amount}, {code}).
 * Account details are never logged, so {@code toString} is redacted.
 */
public record VietQrSettings(
    String bankCode, String accountNumber, String accountName, String imageUrlTemplate) {

  public boolean isConfigured() {
    return !bankCode.isBlank() && !accountNumber.isBlank() && !accountName.isBlank();
  }

  @Override
  public String toString() {
    return "VietQrSettings[redacted]";
  }
}
