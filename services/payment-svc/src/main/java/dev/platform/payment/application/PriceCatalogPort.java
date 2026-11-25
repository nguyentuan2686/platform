package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import java.util.List;
import java.util.Optional;

public interface PriceCatalogPort {

  /** The active price of a product in a currency. */
  Optional<Money> price(String productCode, String currency);

  /** Active prices in a currency, cheapest first. */
  List<CatalogEntry> list(String currency);

  /** Credits a refund must take back; empty for products that are not credit packs (passes). */
  default Optional<Integer> refundableCredits(String productCode) {
    return Optional.empty();
  }
}
