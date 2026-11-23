package dev.platform.order.adapter.out.persistence;

import dev.platform.order.application.OrderRepositoryPort;
import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import dev.platform.order.domain.OrderLine;
import dev.platform.order.domain.OrderStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JdbcClient persistence; joins the caller's transaction. */
@Repository
public class JdbcOrderRepository implements OrderRepositoryPort {

  private final JdbcClient jdbc;

  public JdbcOrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(Order order) {
    jdbc.sql(
            "insert into orders (id, customer_id, status, total, created_at)"
                + " values (:id, :customerId, :status, :total, :createdAt)")
        .param("id", order.id().value())
        .param("customerId", order.customerId())
        .param("status", order.status().name())
        .param("total", order.total())
        .param("createdAt", OffsetDateTime.ofInstant(order.createdAt(), ZoneOffset.UTC))
        .update();

    List<OrderLine> lines = order.lines();
    for (int i = 0; i < lines.size(); i++) {
      OrderLine line = lines.get(i);
      jdbc.sql(
              "insert into order_lines (order_id, line_no, sku, quantity, unit_price)"
                  + " values (:orderId, :lineNo, :sku, :quantity, :unitPrice)")
          .param("orderId", order.id().value())
          .param("lineNo", i)
          .param("sku", line.sku())
          .param("quantity", line.quantity())
          .param("unitPrice", line.unitPrice())
          .update();
    }
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    List<OrderLine> lines =
        jdbc.sql(
                "select sku, quantity, unit_price from order_lines"
                    + " where order_id = :id order by line_no")
            .param("id", id.value())
            .query(
                (rs, n) ->
                    new OrderLine(
                        rs.getString("sku"), rs.getInt("quantity"), rs.getBigDecimal("unit_price")))
            .list();

    return jdbc.sql("select customer_id, status, created_at from orders where id = :id")
        .param("id", id.value())
        .query(
            (rs, n) ->
                Order.restore(
                    id,
                    rs.getString("customer_id"),
                    lines,
                    OrderStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .optional();
  }
}
