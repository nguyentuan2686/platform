package dev.platform.payment.domain;

import java.util.UUID;

/** What recording a provider transaction did. */
public sealed interface MatchOutcome {

  /** The transaction paid this order. */
  record Matched(UUID orderId) implements MatchOutcome {}

  /** The transaction was already recorded; nothing changed. */
  record Duplicate() implements MatchOutcome {}

  /** Recorded, but waiting in the "chưa khớp" queue for a human. */
  record Unmatched(UnmatchedReason reason) implements MatchOutcome {}
}
