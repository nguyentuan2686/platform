package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.MatchOutcome;
import dev.platform.payment.domain.ReceivedVia;

public interface BankTransactionRecorder {

  MatchOutcome record(BankTransaction tx, ReceivedVia via);
}
