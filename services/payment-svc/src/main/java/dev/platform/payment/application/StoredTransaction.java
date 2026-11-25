package dev.platform.payment.application;

import dev.platform.payment.domain.BankTransaction;
import dev.platform.payment.domain.UnmatchedReason;

/** A recorded transaction waiting in the unmatched queue. */
public record StoredTransaction(long id, BankTransaction tx, UnmatchedReason reason) {}
