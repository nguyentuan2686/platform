package dev.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.CreditSource;
import dev.platform.identity.domain.PassKind;
import dev.platform.identity.domain.Subject;
import dev.platform.identity.domain.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class EntitlementUseCaseTest {

  // 2026-11-25 10:00 in Ho Chi Minh
  static final Instant START = Instant.parse("2026-11-25T03:00:00Z");

  InMemoryLedger ledger = new InMemoryLedger();
  InMemoryPorts.Profiles profiles = new InMemoryPorts.Profiles();
  MagicLinkUseCaseTest.MutableClock clock = new MagicLinkUseCaseTest.MutableClock(START);
  EntitlementUseCase useCase;
  PaymentGrantUseCase payments;
  UUID account;

  @BeforeEach
  void setUp() {
    TransactionOperations tx = TransactionOperations.withoutTransaction();
    useCase = new EntitlementUseCase(ledger, profiles, clock, tx);
    payments = new PaymentGrantUseCase(ledger, ledger, clock, tx);
    account = ledger.newAccount();
  }

  private void withProfile(UUID accountId) {
    profiles.insert(
        new Subject.Account(accountId),
        new StoredProfile(UUID.randomUUID(), "Me", new byte[] {1}, 1, Calendar.VN),
        START);
  }

  private void buy(String product) {
    payments.grant(UUID.randomUUID(), UUID.randomUUID(), account, product);
  }

  private int credits() {
    return useCase.view(new Subject.Account(account)).credits();
  }

  private static void assertReason(Throwable thrown, String reason) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            CreditConflictException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  @Test
  void signup_bonus_is_granted_once() {
    useCase.signupBonus(account);
    useCase.signupBonus(account);

    assertThat(credits()).isEqualTo(1);
  }

  @Test
  void signup_bonus_for_an_unknown_account_fails() {
    assertThatThrownBy(() -> useCase.signupBonus(UUID.randomUUID()))
        .isInstanceOf(AccountNotFoundException.class);
  }

  @Test
  void consume_needs_a_profile_credits_and_is_idempotent_on_reading_id() {
    buy("CREDIT_5");
    UUID reading = UUID.randomUUID();
    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(
            () -> useCase.consume(account, reading, Topic.TaiLoc)),
        "no_profile");
    withProfile(account);

    ConsumeResult first = useCase.consume(account, reading, Topic.TaiLoc);
    ConsumeResult again = useCase.consume(account, reading, Topic.TaiLoc);

    assertThat(first).isEqualTo(new ConsumeResult(CreditSource.CREDIT, 4));
    assertThat(again).isEqualTo(first);
    assertThat(credits()).isEqualTo(4);
    assertThat(ledger.birthLocked).containsKey(account);
  }

  @Test
  void no_credits_is_a_409_and_does_not_lock_the_birth() {
    withProfile(account);

    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(
            () -> useCase.consume(account, UUID.randomUUID(), Topic.SucKhoe)),
        "no_credits");
    assertThat(ledger.birthLocked).isEmpty();
  }

  @Test
  void a_reading_id_used_by_another_account_conflicts() {
    buy("CREDIT_1");
    withProfile(account);
    UUID reading = UUID.randomUUID();
    useCase.consume(account, reading, Topic.TaiLoc);
    UUID other = ledger.newAccount();

    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(
            () -> useCase.consume(other, reading, Topic.TaiLoc)),
        "reading_conflict");
  }

  @Test
  void month_pass_is_spent_before_credits_and_refund_restores_the_question() {
    buy("CREDIT_1");
    buy("PASS_MONTH");
    withProfile(account);
    UUID reading = UUID.randomUUID();

    ConsumeResult result = useCase.consume(account, reading, Topic.TinhDuyen);
    assertThat(result).isEqualTo(new ConsumeResult(CreditSource.PASS, 1));
    assertThat(useCase.view(new Subject.Account(account)).pass().questionsLeft()).isEqualTo(1);

    useCase.refund(reading);
    useCase.refund(reading);

    assertThat(useCase.view(new Subject.Account(account)).pass().questionsLeft()).isEqualTo(2);
    assertThat(credits()).isEqualTo(1);
  }

  @Test
  void refund_of_a_credit_returns_it_and_an_unknown_reading_is_not_found() {
    buy("CREDIT_1");
    withProfile(account);
    UUID reading = UUID.randomUUID();
    useCase.consume(account, reading, Topic.GiaDao);
    assertThat(credits()).isZero();

    useCase.refund(reading);

    assertThat(credits()).isEqualTo(1);
    assertThatThrownBy(() -> useCase.refund(UUID.randomUUID()))
        .isInstanceOf(ConsumptionNotFoundException.class);
  }

  @Test
  void consume_of_a_refunded_reading_is_409_and_charges_nothing() {
    buy("CREDIT_1");
    withProfile(account);
    UUID reading = UUID.randomUUID();
    useCase.consume(account, reading, Topic.TaiLoc);
    useCase.refund(reading);

    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(
            () -> useCase.consume(account, reading, Topic.TaiLoc)),
        "reading_refunded");
    assertThat(credits()).isEqualTo(1);
    // A new reading id is charged normally.
    assertThat(useCase.consume(account, UUID.randomUUID(), Topic.TaiLoc).creditsLeft()).isZero();
  }

  @Test
  void year_pass_view_lists_the_features_and_stops_at_the_daily_cap() {
    buy("PASS_YEAR");
    withProfile(account);

    var view = useCase.view(new Subject.Account(account));
    assertThat(view.pass().kind()).isEqualTo(PassKind.YEAR);
    assertThat(view.pass().questionsLeft()).isNull();
    assertThat(view.features()).containsExactly("daily_detail", "yearly_reading");

    for (int i = 0; i < 30; i++) {
      assertThat(useCase.consume(account, UUID.randomUUID(), Topic.VanNam).source())
          .isEqualTo(CreditSource.PASS);
    }
    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(
            () -> useCase.consume(account, UUID.randomUUID(), Topic.VanNam)),
        "daily_cap");

    clock.advance(Duration.ofHours(14)); // past midnight in Vietnam (UTC+7)
    assertThat(useCase.consume(account, UUID.randomUUID(), Topic.VanNam).source())
        .isEqualTo(CreditSource.PASS);
  }

  @Test
  void view_without_a_pass_has_no_features_and_a_guest_is_forbidden() {
    var view = useCase.view(new Subject.Account(account));

    assertThat(view.pass()).isNull();
    assertThat(view.features()).isEmpty();
    assertThatThrownBy(() -> useCase.view(new Subject.Guest(UUID.randomUUID())))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> useCase.ledger(new Subject.Guest(UUID.randomUUID())))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void ledger_lists_the_newest_entries_first() {
    useCase.signupBonus(account);
    buy("CREDIT_1");

    var entries = useCase.ledger(new Subject.Account(account));

    assertThat(entries)
        .extracting(LedgerEntryView::reason)
        .containsExactly("purchase", "signup_bonus");
  }

  @Test
  void reversal_is_idempotent_and_refused_when_the_credits_are_spent() {
    UUID order = UUID.randomUUID();
    payments.grant(UUID.randomUUID(), order, account, "CREDIT_5");

    useCase.reverse(account, order, 5);
    useCase.reverse(account, order, 5);
    assertThat(credits()).isZero();

    UUID order2 = UUID.randomUUID();
    payments.grant(UUID.randomUUID(), order2, account, "CREDIT_1");
    withProfile(account);
    useCase.consume(account, UUID.randomUUID(), Topic.TaiLoc);
    assertReason(
        org.assertj.core.api.Assertions.catchThrowable(() -> useCase.reverse(account, order2, 1)),
        "insufficient_credits");
    assertThat(credits()).isZero();
  }

  @Test
  void payment_is_idempotent_on_event_id_and_on_order_id() {
    UUID event = UUID.randomUUID();
    UUID order = UUID.randomUUID();

    payments.grant(event, order, account, "CREDIT_5");
    payments.grant(event, order, account, "CREDIT_5");
    payments.grant(UUID.randomUUID(), order, account, "CREDIT_5");

    assertThat(credits()).isEqualTo(5);

    UUID passOrder = UUID.randomUUID();
    payments.grant(UUID.randomUUID(), passOrder, account, "PASS_MONTH");
    payments.grant(UUID.randomUUID(), passOrder, account, "PASS_MONTH");
    assertThat(ledger.passes).hasSize(1);
  }

  @Test
  void payment_for_an_unknown_product_or_account_fails() {
    assertThatThrownBy(
            () -> payments.grant(UUID.randomUUID(), UUID.randomUUID(), account, "CREDIT_9"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                payments.grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CREDIT_1"))
        .isInstanceOf(AccountNotFoundException.class);
  }

  @Test
  void a_month_pass_bought_during_a_year_pass_starts_when_the_year_pass_ends() {
    buy("PASS_YEAR");
    buy("PASS_MONTH");

    var yearEnd = ledger.passes.get(0).endsAt();
    assertThat(ledger.passes.get(1).startsAt()).isEqualTo(yearEnd);
    assertThat(ledger.passes.get(1).endsAt()).isEqualTo(yearEnd.plus(Duration.ofDays(30)));
    buy("PASS_MONTH");
    assertThat(ledger.passes.get(2).startsAt()).isEqualTo(ledger.passes.get(1).endsAt());
  }
}
