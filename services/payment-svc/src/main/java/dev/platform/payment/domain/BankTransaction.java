package dev.platform.payment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An incoming payment as the provider reports it. The transfer content is free text typed by the
 * payer, so it is never printed: {@code toString} redacts it.
 */
public record BankTransaction(
    Provider provider,
    String providerTxId,
    Money amount,
    String content,
    String referenceCode,
    Instant occurredAt) {

  public BankTransaction {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(providerTxId, "providerTxId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  @Override
  public String toString() {
    return "BankTransaction[" + provider + " " + providerTxId + ", content=[redacted]]";
  }
}
