package dev.platform.payment.adapter.out.persistence;

import dev.platform.payment.application.RefundRequestPort;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** refund_request on Postgres; joins the caller's transaction. */
public class JdbcRefundRequests implements RefundRequestPort {

  private static final String COLUMNS =
      "id, order_id, provider, amount, currency, status, provider_refund_id, note, created_at,"
          + " completed_at";

  private final JdbcClient jdbc;

  public JdbcRefundRequests(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RefundRequest> findByOrder(UUID orderId) {
    return jdbc.sql("select " + COLUMNS + " from refund_request where order_id = :orderId")
        .param("orderId", orderId)
        .query(JdbcRefundRequests::map)
        .optional();
  }

  @Override
  public Optional<RefundRequest> findForUpdate(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from refund_request where id = :id for update")
        .param("id", id)
        .query(JdbcRefundRequests::map)
        .optional();
  }

  @Override
  public boolean insert(RefundRequest request) {
    return jdbc.sql(
                "insert into refund_request (id, order_id, provider, amount, currency, status,"
                    + " provider_refund_id, note, created_at, completed_at) values (:id, :orderId,"
                    + " :provider, :amount, :currency, :status, :refundId, :note, :createdAt,"
                    + " :completedAt) on conflict (order_id) do nothing")
            .param("id", request.id())
            .param("orderId", request.orderId())
            .param("provider", request.provider().name())
            .param("amount", request.amount().amount())
            .param("currency", request.amount().currency())
            .param("status", request.status().name())
            .param("refundId", request.providerRefundId())
            .param("note", request.note())
            .param("createdAt", Timestamps.toDb(request.createdAt()))
            .param(
                "completedAt",
                Timestamps.toDb(request.completedAt()),
                java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .update()
        == 1;
  }

  @Override
  public void save(RefundRequest request) {
    jdbc.sql(
            "update refund_request set status = :status, provider_refund_id = :refundId,"
                + " note = :note, completed_at = :completedAt where id = :id")
        .param("status", request.status().name())
        .param("refundId", request.providerRefundId())
        .param("note", request.note())
        .param(
            "completedAt",
            Timestamps.toDb(request.completedAt()),
            java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("id", request.id())
        .update();
  }

  @Override
  public List<RefundRequest> list(RefundStatus status) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from refund_request where status = :status order by created_at, id")
        .param("status", status.name())
        .query(JdbcRefundRequests::map)
        .list();
  }

  private static RefundRequest map(ResultSet rs, int rowNum) throws SQLException {
    return new RefundRequest(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        Provider.valueOf(rs.getString("provider")),
        new Money(rs.getBigDecimal("amount"), rs.getString("currency")),
        RefundStatus.valueOf(rs.getString("status")),
        rs.getString("provider_refund_id"),
        rs.getString("note"),
        Timestamps.read(rs, "created_at"),
        Timestamps.read(rs, "completed_at"));
  }
}
