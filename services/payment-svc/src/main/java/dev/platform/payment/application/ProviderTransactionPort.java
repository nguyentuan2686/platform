package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.ReceivedVia;
import dev.platform.payment.domain.UnmatchedReason;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** The ledger of provider transactions; unique on (provider, provider tx id). */
public interface ProviderTransactionPort {

  /**
   * Records the transaction as UNMATCHED and returns its id, or empty (nothing written) when this
   * provider transaction is already there. Race-safe: concurrent inserts of the same transaction
   * yield exactly one id.
   */
  OptionalLong insertIfAbsent(BankTransaction tx, ReceivedVia via);

  void markMatched(long id, UUID orderId);

  /** Keeps the transaction in the queue with a reason; {@code orderId} when one order was found. */
  void markUnmatched(long id, UnmatchedReason reason, UUID orderId);

  /** Oldest first. */
  List<StoredTransaction> listUnmatched();

  /** An UNMATCHED transaction, locked for the caller's transaction. */
  Optional<StoredTransaction> findUnmatchedForUpdate(long id);

  /** A human matched the transaction to an order. */
  void assign(long id, UUID orderId);

  /** A human dealt with the transaction outside the system. */
  void resolve(long id, String resolution, String note);

  /** The provider transaction (a PayPal capture) that paid the order. */
  Optional<String> matchedTransactionId(UUID orderId, Provider provider);
}
