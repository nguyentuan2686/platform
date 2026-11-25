package dev.platform.payment.adapter.out.persistence;

import dev.platform.payment.application.CatalogEntry;
import dev.platform.payment.application.PriceCatalogPort;
import dev.platform.payment.domain.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcPriceCatalog implements PriceCatalogPort {

  private final JdbcClient jdbc;

  public JdbcPriceCatalog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Money> price(String productCode, String currency) {
    return jdbc.sql(
            "select amount from price"
                + " where product_code = :code and currency = :currency and active")
        .param("code", productCode)
        .param("currency", currency)
        .query((rs, n) -> new Money(rs.getBigDecimal("amount"), currency))
        .optional();
  }

  @Override
  public Optional<Integer> refundableCredits(String productCode) {
    return jdbc.sql(
            "select refundable_credits from price where product_code = :code"
                + " and refundable_credits is not null order by currency limit 1")
        .param("code", productCode)
        .query(Integer.class)
        .optional();
  }

  @Override
  public List<CatalogEntry> list(String currency) {
    return jdbc.sql(
            "select product_code, amount from price where currency = :currency and active"
                + " order by amount, product_code")
        .param("currency", currency)
        .query(
            (rs, n) ->
                new CatalogEntry(
                    rs.getString("product_code"), new Money(rs.getBigDecimal("amount"), currency)))
        .list();
  }
}
