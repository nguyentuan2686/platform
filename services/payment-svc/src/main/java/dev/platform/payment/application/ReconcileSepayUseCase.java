package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.ReceivedVia;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safety net for lost webhooks: feeds the bank's recent transactions through the same recorder. The
 * SePay call happens outside any database transaction (each record call has its own).
 */
public class ReconcileSepayUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ReconcileSepayUseCase.class);

  public record Summary(int total, int matched, int unmatched, int duplicates, int failed) {}

  private final BankTransactionsPort bank;
  private final BankTransactionRecorder recorder;
  private final Duration lookback;

  public ReconcileSepayUseCase(
      BankTransactionsPort bank, BankTransactionRecorder recorder, Duration lookback) {
    this.bank = bank;
    this.recorder = recorder;
    this.lookback = lookback;
  }

  /**
   * Propagates {@link UpstreamUnavailableException}; one bad transaction does not stop the rest.
   */
  public Summary run() {
    List<BankTransaction> recent = bank.recent(lookback);
    int matched = 0;
    int unmatched = 0;
    int duplicates = 0;
    int failed = 0;
    for (BankTransaction tx : recent) {
      try {
        MatchOutcome outcome = recorder.record(tx, ReceivedVia.RECONCILE);
        switch (outcome) {
          case MatchOutcome.Matched m -> matched++;
          case MatchOutcome.Unmatched u -> unmatched++;
          case MatchOutcome.Duplicate d -> duplicates++;
        }
      } catch (RuntimeException e) {
        failed++;
        LOG.warn(
            "sepay reconcile could not record provider_tx_id={}: {}",
            tx.providerTxId(),
            e.getClass().getSimpleName());
      }
    }
    return new Summary(recent.size(), matched, unmatched, duplicates, failed);
  }
}
