package dev.platform.payment.adapter.out.persistence;

import dev.platform.payment.application.ProviderTransactionPort;
import dev.platform.payment.application.StoredTransaction;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.UnmatchedReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** provider_transaction on Postgres; joins the caller's transaction. */
public class JdbcProviderTransactions implements ProviderTransactionPort {

  private static final String COLUMNS =
      "id, provider, provider_tx_id, amount, currency, content, reference_code, occurred_at,"
          + " unmatched_reason";

  private final JdbcClient jdbc;

  public JdbcProviderTransactions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public OptionalLong insertIfAbsent(BankTransaction tx, ReceivedVia via) {
    // ON CONFLICT DO NOTHING waits for a concurrent uncommitted insert of the same key, then
    // yields no row: exactly one caller gets an id, however many race.
    Optional<Long> id =
        jdbc.sql(
                "insert into provider_transaction (provider, provider_tx_id, amount, currency,"
                    + " content, reference_code, occurred_at, received_via, match_status)"
                    + " values (:provider, :txId, :amount, :currency, :content, :reference,"
                    + " :occurredAt, :via, 'UNMATCHED')"
                    + " on conflict (provider, provider_tx_id) do nothing returning id")
            .param("provider", tx.provider().name())
            .param("txId", tx.providerTxId())
            .param("amount", tx.amount().amount())
            .param("currency", tx.amount().currency())
            .param("content", tx.content())
            .param("reference", tx.referenceCode())
            .param("occurredAt", Timestamps.toDb(tx.occurredAt()))
            .param("via", via.name())
            .query((rs, n) -> rs.getLong(1))
            .optional();
    return id.map(OptionalLong::of).orElseGet(OptionalLong::empty);
  }

  @Override
  public void markMatched(long id, UUID orderId) {
    jdbc.sql(
            "update provider_transaction set match_status = 'MATCHED', unmatched_reason = null,"
                + " order_id = :orderId where id = :id")
        .param("orderId", orderId)
        .param("id", id)
        .update();
  }

  @Override
  public void markUnmatched(long id, UnmatchedReason reason, UUID orderId) {
    jdbc.sql(
            "update provider_transaction set match_status = 'UNMATCHED',"
                + " unmatched_reason = :reason, order_id = :orderId where id = :id")
        .param("reason", reason.name())
        .param("orderId", orderId, java.sql.Types.OTHER)
        .param("id", id)
        .update();
  }

  @Override
  public List<StoredTransaction> listUnmatched() {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from provider_transaction where match_status = 'UNMATCHED' order by id")
        .query(JdbcProviderTransactions::map)
        .list();
  }

  @Override
  public Optional<StoredTransaction> findUnmatchedForUpdate(long id) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from provider_transaction where id = :id and match_status = 'UNMATCHED'"
                + " for update")
        .param("id", id)
        .query(JdbcProviderTransactions::map)
        .optional();
  }

  @Override
  public void assign(long id, UUID orderId) {
    jdbc.sql(
            "update provider_transaction set match_status = 'MATCHED', order_id = :orderId,"
                + " resolution = 'ASSIGNED' where id = :id")
        .param("orderId", orderId)
        .param("id", id)
        .update();
  }

  @Override
  public void resolve(long id, String resolution, String note) {
    jdbc.sql(
            "update provider_transaction set match_status = 'RESOLVED', resolution = :resolution,"
                + " note = :note where id = :id")
        .param("resolution", resolution)
        .param("note", note)
        .param("id", id)
        .update();
  }

  @Override
  public Optional<String> matchedTransactionId(UUID orderId, Provider provider) {
    return jdbc.sql(
            "select provider_tx_id from provider_transaction where order_id = :orderId"
                + " and provider = :provider and match_status = 'MATCHED' order by id limit 1")
        .param("orderId", orderId)
        .param("provider", provider.name())
        .query(String.class)
        .optional();
  }

  private static StoredTransaction map(ResultSet rs, int rowNum) throws SQLException {
    String reason = rs.getString("unmatched_reason");
    return new StoredTransaction(
        rs.getLong("id"),
        new BankTransaction(
            Provider.valueOf(rs.getString("provider")),
            rs.getString("provider_tx_id"),
            new Money(rs.getBigDecimal("amount"), rs.getString("currency")),
            rs.getString("content"),
            rs.getString("reference_code"),
            Timestamps.read(rs, "occurred_at")),
        reason == null ? null : UnmatchedReason.valueOf(reason));
  }
}
