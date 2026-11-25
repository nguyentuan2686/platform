package dev.platform.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount in a currency, always two decimals (matches numeric(12,2) and the event). */
public record Money(BigDecimal amount, String currency) {

  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    amount = amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  public static Money of(String amount, String currency) {
    return new Money(new BigDecimal(amount), currency);
  }

  public boolean isLessThan(Money other) {
    return compare(other) < 0;
  }

  public boolean isGreaterThan(Money other) {
    return compare(other) > 0;
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public String toPlainString() {
    return amount.toPlainString();
  }

  private int compare(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("currency mismatch");
    }
  }
}
