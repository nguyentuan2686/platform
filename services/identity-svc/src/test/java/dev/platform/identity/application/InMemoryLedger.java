package dev.platform.identity.application;

import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.Entitlements;
import dev.platform.identity.domain.PassKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory fake of the ledger, the passes and the processed events. */
final class InMemoryLedger implements LedgerPort, ProcessedEventPort {

  record PassRow(
      UUID id,
      UUID accountId,
      PassKind kind,
      UUID orderId,
      Instant startsAt,
      Instant endsAt,
      Integer questions) {}

  final Set<UUID> accounts = new HashSet<>();
  final List<Entry> entries = new ArrayList<>();
  final List<PassRow> passes = new ArrayList<>();
  final Set<UUID> processed = new HashSet<>();
  final Map<UUID, Instant> birthLocked = new HashMap<>();

  UUID newAccount() {
    UUID id = UUID.randomUUID();
    accounts.add(id);
    return id;
  }

  @Override
  public boolean lockAccount(UUID accountId) {
    return accounts.contains(accountId);
  }

  @Override
  public Entitlements load(UUID accountId, Instant todayStart) {
    int credits =
        entries.stream()
            .filter(e -> e.accountId().equals(accountId) && e.source() == CreditSource.CREDIT)
            .mapToInt(Entry::delta)
            .sum();
    List<Entitlements.PassState> states =
        passes.stream()
            .filter(p -> p.accountId().equals(accountId))
            .map(
                p ->
                    new Entitlements.PassState(
                        p.id(),
                        p.kind(),
                        p.startsAt(),
                        p.endsAt(),
                        p.questions(),
                        -entries.stream()
                            .filter(e -> p.id().equals(e.passId()))
                            .mapToInt(Entry::delta)
                            .sum()))
            .toList();
    List<Entitlements.PassUse> uses =
        entries.stream()
            .filter(e -> e.accountId().equals(accountId) && e.source() == CreditSource.PASS)
            .filter(e -> !e.createdAt().isBefore(todayStart))
            .map(e -> new Entitlements.PassUse(e.passId(), e.delta(), e.createdAt()))
            .toList();
    return new Entitlements(credits, states, uses);
  }

  @Override
  public Optional<Entry> find(String reason, String ref) {
    return entries.stream()
        .filter(e -> e.reason().equals(reason) && e.ref().equals(ref))
        .findFirst();
  }

  @Override
  public boolean append(NewEntry e) {
    if (find(e.reason(), e.ref()).isPresent()) {
      return false;
    }
    entries.add(
        new Entry(
            e.accountId(),
            e.delta(),
            e.source(),
            e.passId(),
            e.reason(),
            e.ref(),
            e.topic(),
            e.at()));
    return true;
  }

  @Override
  public Optional<Instant> latestPassEnd(UUID accountId) {
    return passes.stream()
        .filter(p -> p.accountId().equals(accountId))
        .map(PassRow::endsAt)
        .max(Instant::compareTo);
  }

  @Override
  public boolean insertPass(
      UUID id,
      UUID accountId,
      PassKind kind,
      UUID orderId,
      Instant startsAt,
      Instant endsAt,
      Integer questions) {
    if (passes.stream().anyMatch(p -> p.orderId().equals(orderId))) {
      return false;
    }
    passes.add(new PassRow(id, accountId, kind, orderId, startsAt, endsAt, questions));
    return true;
  }

  @Override
  public List<Entry> recent(UUID accountId, int limit) {
    List<Entry> mine =
        new ArrayList<>(entries.stream().filter(e -> e.accountId().equals(accountId)).toList());
    Collections.reverse(mine);
    return mine.stream().limit(limit).toList();
  }

  @Override
  public void lockBirth(UUID accountId, Instant now) {
    birthLocked.putIfAbsent(accountId, now);
  }

  @Override
  public boolean markProcessed(UUID eventId) {
    return processed.add(eventId);
  }
}
