package dev.platform.identity.application;

import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.ProductGrant;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Applies a paid order. Idempotent on the event id and, independently, on the order id (a replayed
 * payment with a new event id grants once).
 */
public class PaymentGrantUseCase {

  private final LedgerPort ledger;
  private final ProcessedEventPort processed;
  private final Clock clock;
  private final TransactionOperations tx;

  public PaymentGrantUseCase(
      LedgerPort ledger, ProcessedEventPort processed, Clock clock, TransactionOperations tx) {
    this.ledger = ledger;
    this.processed = processed;
    this.clock = clock;
    this.tx = tx;
  }

  /**
   * Throws {@link IllegalArgumentException} for an unknown product and {@link
   * AccountNotFoundException} for an unknown account; both roll back so the event can go to the
   * dead-letter topic without leaving a processed marker.
   */
  public void grant(UUID eventId, UUID orderId, UUID accountId, String productCode) {
    ProductGrant grant = ProductGrant.of(productCode);
    tx.executeWithoutResult(
        status -> {
          if (!processed.markProcessed(eventId)) {
            return;
          }
          if (!ledger.lockAccount(accountId)) {
            throw new AccountNotFoundException();
          }
          Instant now = clock.instant();
          switch (grant) {
            case ProductGrant.Credits credits ->
                ledger.append(
                    new LedgerPort.NewEntry(
                        accountId,
                        credits.n(),
                        CreditSource.CREDIT,
                        null,
                        "purchase",
                        orderId.toString(),
                        null,
                        now));
            case ProductGrant.Pass pass -> {
              Instant starts = pass.startsAt(now, ledger.latestPassEnd(accountId));
              ledger.insertPass(
                  UUID.randomUUID(),
                  accountId,
                  pass.kind(),
                  orderId,
                  starts,
                  pass.endsAt(starts),
                  pass.questions());
            }
          }
        });
  }
}
