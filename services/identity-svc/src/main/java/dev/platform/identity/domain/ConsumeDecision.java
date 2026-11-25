package dev.platform.identity.domain;

import java.util.UUID;

/** The outcome of asking to spend one unit. {@code passId} is null for {@code CREDIT}. */
public sealed interface ConsumeDecision {

  record Use(CreditSource source, UUID passId) implements ConsumeDecision {}

  /** {@code reason} is {@code no_credits} or {@code daily_cap}. */
  record Denied(String reason) implements ConsumeDecision {}
}
