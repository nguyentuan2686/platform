package dev.platform.payment.support;

import dev.platform.payment.application.ProviderTransactionPort;
import dev.platform.payment.application.StoredTransaction;
import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.UnmatchedReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** In-memory ledger of provider transactions with the same uniqueness rule as the table. */
public class InMemoryProviderTransactions implements ProviderTransactionPort {

  public record Row(
      long id,
      BankTransaction tx,
      ReceivedVia via,
      String status,
      UnmatchedReason reason,
      UUID orderId,
      String resolution,
      String note) {}

  public final Map<Long, Row> rows = new LinkedHashMap<>();
  private long seq;

  @Override
  public OptionalLong insertIfAbsent(BankTransaction tx, ReceivedVia via) {
    boolean exists =
        rows.values().stream()
            .anyMatch(
                r ->
                    r.tx().provider() == tx.provider()
                        && r.tx().providerTxId().equals(tx.providerTxId()));
    if (exists) {
      return OptionalLong.empty();
    }
    long id = ++seq;
    rows.put(id, new Row(id, tx, via, "UNMATCHED", null, null, null, null));
    return OptionalLong.of(id);
  }

  @Override
  public void markMatched(long id, UUID orderId) {
    Row r = rows.get(id);
    rows.put(id, new Row(id, r.tx(), r.via(), "MATCHED", null, orderId, null, null));
  }

  @Override
  public void markUnmatched(long id, UnmatchedReason reason, UUID orderId) {
    Row r = rows.get(id);
    rows.put(id, new Row(id, r.tx(), r.via(), "UNMATCHED", reason, orderId, null, null));
  }

  @Override
  public List<StoredTransaction> listUnmatched() {
    List<StoredTransaction> out = new ArrayList<>();
    for (Row r : rows.values()) {
      if (r.status().equals("UNMATCHED")) {
        out.add(new StoredTransaction(r.id(), r.tx(), r.reason()));
      }
    }
    return out;
  }

  @Override
  public Optional<StoredTransaction> findUnmatchedForUpdate(long id) {
    return listUnmatched().stream().filter(s -> s.id() == id).findFirst();
  }

  @Override
  public void assign(long id, UUID orderId) {
    Row r = rows.get(id);
    rows.put(id, new Row(id, r.tx(), r.via(), "MATCHED", r.reason(), orderId, "ASSIGNED", null));
  }

  @Override
  public void resolve(long id, String resolution, String note) {
    Row r = rows.get(id);
    rows.put(
        id, new Row(id, r.tx(), r.via(), "RESOLVED", r.reason(), r.orderId(), resolution, note));
  }

  @Override
  public Optional<String> matchedTransactionId(UUID orderId, Provider provider) {
    return rows.values().stream()
        .filter(r -> orderId.equals(r.orderId()) && r.tx().provider() == provider)
        .map(r -> r.tx().providerTxId())
        .findFirst();
  }
}
