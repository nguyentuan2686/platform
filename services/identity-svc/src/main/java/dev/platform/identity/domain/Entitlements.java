package dev.platform.identity.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An account's spendable state: the credit balance, its passes and the pass ledger entries of the
 * current day (consumes are -1, refunds +1).
 */
public record Entitlements(int credits, List<PassState> passes, List<PassUse> passUses) {

  /** Hidden fair-use cap of the year pass. */
  public static final int YEAR_DAILY_CAP = 30;

  public Entitlements {
    passes = List.copyOf(passes);
    passUses = List.copyOf(passUses);
  }

  public record PassState(
      UUID id,
      PassKind kind,
      Instant startsAt,
      Instant endsAt,
      Integer questionsIncluded,
      int questionsUsed) {

    public boolean activeAt(Instant now) {
      return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /** Null means unlimited. */
    public Integer questionsLeft() {
      return questionsIncluded == null ? null : Math.max(0, questionsIncluded - questionsUsed);
    }
  }

  public record PassUse(UUID passId, int delta, Instant at) {}

  /** The start of the calendar day containing {@code now} in {@code zone}. */
  public static Instant startOfDay(Instant now, ZoneId zone) {
    return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
  }

  /**
   * Source order: active year pass (unless its daily cap is hit), active month pass with questions
   * left (earliest ending first), credits.
   */
  public ConsumeDecision decide(Instant now, ZoneId zone) {
    Optional<PassState> yearly =
        passes.stream().filter(p -> p.kind() == PassKind.YEAR && p.activeAt(now)).findFirst();
    if (yearly.isPresent()) {
      Instant from = startOfDay(now, zone);
      Instant to = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      int today =
          passUses.stream()
              .filter(u -> u.passId().equals(yearly.get().id()))
              .filter(u -> !u.at().isBefore(from) && u.at().isBefore(to))
              .mapToInt(u -> -u.delta())
              .sum();
      if (today >= YEAR_DAILY_CAP) {
        return new ConsumeDecision.Denied("daily_cap");
      }
      return new ConsumeDecision.Use(CreditSource.PASS, yearly.get().id());
    }
    Optional<PassState> monthly =
        passes.stream()
            .filter(p -> p.kind() == PassKind.MONTH && p.activeAt(now) && p.questionsLeft() > 0)
            .min(Comparator.comparing(PassState::endsAt));
    if (monthly.isPresent()) {
      return new ConsumeDecision.Use(CreditSource.PASS, monthly.get().id());
    }
    if (credits > 0) {
      return new ConsumeDecision.Use(CreditSource.CREDIT, null);
    }
    return new ConsumeDecision.Denied("no_credits");
  }

  /** The pass to show: the active year pass, else the active month pass ending first. */
  public Optional<PassState> activePass(Instant now) {
    return passes.stream()
        .filter(p -> p.activeAt(now))
        .min(
            Comparator.comparing((PassState p) -> p.kind() == PassKind.YEAR ? 0 : 1)
                .thenComparing(PassState::endsAt));
  }
}
