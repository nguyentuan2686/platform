package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import java.time.Duration;
import java.util.List;

public interface BankTransactionsPort {

  /**
   * Incoming transactions of the last {@code lookback}; throws {@link
   * UpstreamUnavailableException}.
   */
  List<BankTransaction> recent(Duration lookback);
}
