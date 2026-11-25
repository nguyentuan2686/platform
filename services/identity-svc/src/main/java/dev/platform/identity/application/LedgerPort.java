package dev.platform.identity.application;

import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.Entitlements;
import dev.platform.identity.domain.PassKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The credit ledger and passes. Every method joins the caller's transaction. */
public interface LedgerPort {

  /** Takes the per-account row lock that serialises every balance change; false if no account. */
  boolean lockAccount(UUID accountId);

  Entitlements load(UUID accountId, Instant todayStart);

  Optional<Entry> find(String reason, String ref);

  /** False when {@code (reason, ref)} already exists (nothing is written). */
  boolean append(NewEntry entry);

  Optional<Instant> latestPassEnd(UUID accountId);

  /** False when the order already has a pass. */
  boolean insertPass(
      UUID id,
      UUID accountId,
      PassKind kind,
      UUID orderId,
      Instant startsAt,
      Instant endsAt,
      Integer questions);

  List<Entry> recent(UUID accountId, int limit);

  /** Sets the account's birth lock once; later calls keep the first time. */
  void lockBirth(UUID accountId, Instant now);

  record NewEntry(
      UUID accountId,
      int delta,
      CreditSource source,
      UUID passId,
      String reason,
      String ref,
      String topic,
      Instant at) {}

  record Entry(
      UUID accountId,
      int delta,
      CreditSource source,
      UUID passId,
      String reason,
      String ref,
      String topic,
      Instant createdAt) {}
}
