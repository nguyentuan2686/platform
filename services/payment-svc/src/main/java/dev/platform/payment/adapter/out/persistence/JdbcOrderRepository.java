package dev.platform.payment.adapter.out.persistence;

import dev.platform.payment.application.OrderRepositoryPort;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.OrderStatus;
import dev.platform.payment.domain.Provider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Plain JdbcClient persistence; joins the caller's transaction. */
public class JdbcOrderRepository implements OrderRepositoryPort {

  private static final String COLUMNS =
      "id, code, account_id, site, product_code, chart_profile_id, amount, currency, provider,"
          + " status, provider_ref, note, created_at, expires_at, paid_at";

  private final JdbcClient jdbc;

  public JdbcOrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(Order order) {
    int rows =
        jdbc.sql(
                "insert into orders (id, code, account_id, site, product_code, chart_profile_id,"
                    + " amount, currency, provider, status, provider_ref, note, created_at,"
                    + " expires_at, paid_at) values (:id, :code, :accountId, :site, :productCode,"
                    + " :chartProfileId, :amount, :currency, :provider, :status, :providerRef,"
                    + " :note, :createdAt, :expiresAt, :paidAt)"
                    + " on conflict (code) do nothing")
            .param("id", order.id())
            .param("code", order.code().value())
            .param("accountId", order.accountId())
            .param("site", order.site())
            .param("productCode", order.productCode())
            .param("chartProfileId", order.chartProfileId(), java.sql.Types.OTHER)
            .param("amount", order.amount().amount())
            .param("currency", order.amount().currency())
            .param("provider", order.provider().name())
            .param("status", order.status().name())
            .param("providerRef", order.providerRef())
            .param("note", order.note())
            .param("createdAt", Timestamps.toDb(order.createdAt()))
            .param("expiresAt", Timestamps.toDb(order.expiresAt()))
            .param(
                "paidAt", Timestamps.toDb(order.paidAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .update();
    return rows == 1;
  }

  @Override
  public void updateProviderRef(UUID id, String providerRef) {
    jdbc.sql("update orders set provider_ref = :ref where id = :id")
        .param("ref", providerRef)
        .param("id", id)
        .update();
  }

  @Override
  public Optional<Order> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from orders where id = :id")
        .param("id", id)
        .query(JdbcOrderRepository::map)
        .optional();
  }

  @Override
  public Optional<Order> findForUpdate(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from orders where id = :id for update")
        .param("id", id)
        .query(JdbcOrderRepository::map)
        .optional();
  }

  @Override
  public List<Order> findByAccount(UUID accountId, int limit) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from orders where account_id = :accountId"
                + " order by created_at desc, id limit :limit")
        .param("accountId", accountId)
        .param("limit", limit)
        .query(JdbcOrderRepository::map)
        .list();
  }

  @Override
  public void save(Order order) {
    jdbc.sql(
            "update orders set status = :status, provider_ref = :providerRef, note = :note,"
                + " paid_at = :paidAt where id = :id")
        .param("status", order.status().name())
        .param("providerRef", order.providerRef())
        .param("note", order.note())
        .param("paidAt", Timestamps.toDb(order.paidAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("id", order.id())
        .update();
  }

  private static Order map(ResultSet rs, int rowNum) throws SQLException {
    return Order.restore(
        rs.getObject("id", UUID.class),
        new OrderCode(rs.getString("code")),
        rs.getObject("account_id", UUID.class),
        rs.getString("site"),
        rs.getString("product_code"),
        rs.getObject("chart_profile_id", UUID.class),
        new Money(rs.getBigDecimal("amount"), rs.getString("currency")),
        Provider.valueOf(rs.getString("provider")),
        OrderStatus.valueOf(rs.getString("status")),
        rs.getString("provider_ref"),
        rs.getString("note"),
        Timestamps.read(rs, "created_at"),
        Timestamps.read(rs, "expires_at"),
        Timestamps.read(rs, "paid_at"));
  }
}
