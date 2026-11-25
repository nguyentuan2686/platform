package dev.platform.payment.application;

import java.util.List;
import java.util.Set;

public class CatalogUseCase {

  static final Set<String> SITES = Set.of("tuvi", "astro");
  static final Set<String> CURRENCIES = Set.of("VND", "USD");

  private final PriceCatalogPort catalog;

  public CatalogUseCase(PriceCatalogPort catalog) {
    this.catalog = catalog;
  }

  public List<CatalogEntry> list(String site, String currency) {
    if (site == null || !SITES.contains(site)) {
      throw new OrderValidationException("site", "unknown site");
    }
    if (currency == null || !CURRENCIES.contains(currency)) {
      throw new OrderValidationException("currency", "must be VND or USD");
    }
    return catalog.list(currency);
  }
}
