package dev.platform.identity.application;

import dev.platform.identity.domain.ConsumeDecision;
import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.Entitlements;
import dev.platform.identity.domain.PassKind;
import dev.platform.identity.domain.Subject;
import dev.platform.identity.domain.Topic;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Spending and returning units. Every balance change first takes the account's row lock, so
 * concurrent consumes of the last credit are serialised and the balance never goes negative.
 */
public class EntitlementUseCase implements EntitlementGrantPort {

  public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final int LEDGER_PAGE = 50;

  private final LedgerPort ledger;
  private final ProfileRepositoryPort profiles;
  private final Clock clock;
  private final TransactionOperations tx;

  public EntitlementUseCase(
      LedgerPort ledger, ProfileRepositoryPort profiles, Clock clock, TransactionOperations tx) {
    this.ledger = ledger;
    this.profiles = profiles;
    this.clock = clock;
    this.tx = tx;
  }

  /** Once per account: the unique {@code (signup_bonus, account id)} key. */
  @Override
  public void signupBonus(UUID accountId) {
    tx.executeWithoutResult(
        status -> {
          if (!ledger.lockAccount(accountId)) {
            throw new AccountNotFoundException();
          }
          ledger.append(
              new LedgerPort.NewEntry(
                  accountId,
                  1,
                  CreditSource.CREDIT,
                  null,
                  "signup_bonus",
                  accountId.toString(),
                  null,
                  clock.instant()));
        });
  }

  public EntitlementView view(Subject subject) {
    UUID accountId = account(subject);
    Instant now = clock.instant();
    Entitlements e = tx.execute(status -> load(accountId, now));
    EntitlementView.PassView pass =
        e.activePass(now)
            .map(p -> new EntitlementView.PassView(p.kind(), p.endsAt(), p.questionsLeft()))
            .orElse(null);
    List<String> features = new ArrayList<>();
    if (pass != null) {
      features.add("daily_detail");
      if (pass.kind() == PassKind.YEAR) {
        features.add("yearly_reading");
      }
    }
    return new EntitlementView(e.credits(), pass, List.copyOf(features));
  }

  public List<LedgerEntryView> ledger(Subject subject) {
    UUID accountId = account(subject);
    return tx.execute(status -> ledger.recent(accountId, LEDGER_PAGE)).stream()
        .map(e -> new LedgerEntryView(e.delta(), e.source(), e.reason(), e.topic(), e.createdAt()))
        .toList();
  }

  /**
   * Spends one unit for the reading; a repeated reading id answers the same result without a second
   * charge. Needs a chart profile; the first success locks the account's birth data.
   */
  public ConsumeResult consume(UUID accountId, UUID readingId, Topic topic) {
    return tx.execute(
        status -> {
          if (!ledger.lockAccount(accountId)) {
            throw new AccountNotFoundException();
          }
          Instant now = clock.instant();
          var existing = ledger.find("consume", readingId.toString());
          if (existing.isPresent()) {
            if (!existing.get().accountId().equals(accountId)) {
              throw new CreditConflictException("reading_conflict");
            }
            if (ledger.find("refund", readingId.toString()).isPresent()) {
              // The unit went back: a retry must use a new reading id, not get a free 200.
              throw new CreditConflictException("reading_refunded");
            }
            return new ConsumeResult(existing.get().source(), load(accountId, now).credits());
          }
          if (profiles.find(new Subject.Account(accountId)).isEmpty()) {
            throw new CreditConflictException("no_profile");
          }
          Entitlements e = load(accountId, now);
          return switch (e.decide(now, VIETNAM)) {
            case ConsumeDecision.Denied denied ->
                throw new CreditConflictException(denied.reason());
            case ConsumeDecision.Use use -> {
              boolean written =
                  ledger.append(
                      new LedgerPort.NewEntry(
                          accountId,
                          -1,
                          use.source(),
                          use.passId(),
                          "consume",
                          readingId.toString(),
                          topic.name(),
                          now));
              if (!written) {
                throw new CreditConflictException("reading_conflict");
              }
              ledger.lockBirth(accountId, now);
              int left = use.source() == CreditSource.CREDIT ? e.credits() - 1 : e.credits();
              yield new ConsumeResult(use.source(), left);
            }
          };
        });
  }

  /** Returns the unit to the source it came from. Repeats are no-ops; an unknown reading is 404. */
  public void refund(UUID readingId) {
    tx.executeWithoutResult(
        status -> {
          var consumed =
              ledger
                  .find("consume", readingId.toString())
                  .orElseThrow(ConsumptionNotFoundException::new);
          ledger.lockAccount(consumed.accountId());
          ledger.append(
              new LedgerPort.NewEntry(
                  consumed.accountId(),
                  -consumed.delta(),
                  consumed.source(),
                  consumed.passId(),
                  "refund",
                  readingId.toString(),
                  consumed.topic(),
                  clock.instant()));
        });
  }

  /** Takes back credits of a refunded purchase. Repeats are no-ops; spent credits are 409. */
  public void reverse(UUID accountId, UUID orderId, int credits) {
    tx.executeWithoutResult(
        status -> {
          if (!ledger.lockAccount(accountId)) {
            throw new AccountNotFoundException();
          }
          if (ledger.find("reversal", orderId.toString()).isPresent()) {
            return;
          }
          Instant now = clock.instant();
          if (load(accountId, now).credits() < credits) {
            throw new CreditConflictException("insufficient_credits");
          }
          ledger.append(
              new LedgerPort.NewEntry(
                  accountId,
                  -credits,
                  CreditSource.CREDIT,
                  null,
                  "reversal",
                  orderId.toString(),
                  null,
                  now));
        });
  }

  private Entitlements load(UUID accountId, Instant now) {
    return ledger.load(accountId, Entitlements.startOfDay(now, VIETNAM));
  }

  private static UUID account(Subject subject) {
    if (subject instanceof Subject.Account a) {
      return a.id();
    }
    throw new ForbiddenException();
  }
}
