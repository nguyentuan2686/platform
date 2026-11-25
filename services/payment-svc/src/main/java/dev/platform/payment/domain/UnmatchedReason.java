package dev.platform.payment.domain;

/** Why a provider transaction could not be applied to an order automatically ("chưa khớp"). */
public enum UnmatchedReason {
  NO_CODE,
  UNKNOWN_CODE,
  AMBIGUOUS,
  ALREADY_PAID,
  AMOUNT_LOW
}
