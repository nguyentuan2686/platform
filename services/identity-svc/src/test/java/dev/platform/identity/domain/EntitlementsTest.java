package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.identity.domain.Entitlements.PassState;
import dev.platform.identity.domain.Entitlements.PassUse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntitlementsTest {

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  // 2026-06-10 10:00 in Ho Chi Minh (UTC+7)
  static final Instant NOW = Instant.parse("2026-06-10T03:00:00Z");

  static PassState year(UUID id) {
    return new PassState(
        id, PassKind.YEAR, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400 * 300), null, 0);
  }

  static PassState month(UUID id, int used, long endsInDays) {
    return new PassState(
        id,
        PassKind.MONTH,
        NOW.minusSeconds(86_400),
        NOW.plusSeconds(86_400 * endsInDays),
        2,
        used);
  }

  @Test
  void active_year_pass_is_used_first() {
    UUID y = UUID.randomUUID();
    Entitlements e = new Entitlements(3, List.of(year(y)), List.of());

    assertThat(e.decide(NOW, VN)).isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, y));
  }

  @Test
  void year_pass_stops_at_30_consumes_in_the_vietnam_day_and_resumes_at_midnight() {
    UUID y = UUID.randomUUID();
    Instant startOfVnDay = Instant.parse("2026-06-09T17:00:00Z"); // 00:00 on 2026-06-10 in VN
    List<PassUse> uses = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      uses.add(new PassUse(y, -1, startOfVnDay.plusSeconds(60L * i)));
    }
    Entitlements e = new Entitlements(5, List.of(year(y)), uses);

    assertThat(e.decide(NOW, VN)).isEqualTo(new ConsumeDecision.Denied("daily_cap"));
    // 23:59:59 VN is still the same day, 00:00 VN the next day is allowed again.
    assertThat(e.decide(Instant.parse("2026-06-10T16:59:59Z"), VN))
        .isEqualTo(new ConsumeDecision.Denied("daily_cap"));
    assertThat(e.decide(Instant.parse("2026-06-10T17:00:00Z"), VN))
        .isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, y));
  }

  @Test
  void a_refunded_consume_does_not_count_against_the_cap() {
    UUID y = UUID.randomUUID();
    Instant startOfVnDay = Instant.parse("2026-06-09T17:00:00Z");
    List<PassUse> uses = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      uses.add(new PassUse(y, -1, startOfVnDay.plusSeconds(60L * i)));
    }
    uses.add(new PassUse(y, 1, startOfVnDay.plusSeconds(4000)));

    assertThat(new Entitlements(0, List.of(year(y)), uses).decide(NOW, VN))
        .isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, y));
  }

  @Test
  void month_pass_with_questions_left_before_credits_earliest_ending_first() {
    UUID soon = UUID.randomUUID();
    UUID later = UUID.randomUUID();
    Entitlements e =
        new Entitlements(5, List.of(month(later, 0, 20), month(soon, 1, 5)), List.of());

    assertThat(e.decide(NOW, VN)).isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, soon));
  }

  @Test
  void exhausted_month_pass_falls_through_to_credits() {
    Entitlements e = new Entitlements(1, List.of(month(UUID.randomUUID(), 2, 10)), List.of());

    assertThat(e.decide(NOW, VN)).isEqualTo(new ConsumeDecision.Use(CreditSource.CREDIT, null));
  }

  @Test
  void expired_and_future_passes_are_ignored() {
    PassState expired =
        new PassState(
            UUID.randomUUID(), PassKind.YEAR, NOW.minusSeconds(1000), NOW.minusSeconds(1), null, 0);
    PassState future =
        new PassState(
            UUID.randomUUID(), PassKind.MONTH, NOW.plusSeconds(1), NOW.plusSeconds(9999), 2, 0);

    assertThat(new Entitlements(0, List.of(expired, future), List.of()).decide(NOW, VN))
        .isEqualTo(new ConsumeDecision.Denied("no_credits"));
  }

  @Test
  void no_pass_and_no_credits() {
    assertThat(new Entitlements(0, List.of(), List.of()).decide(NOW, VN))
        .isEqualTo(new ConsumeDecision.Denied("no_credits"));
  }

  @Test
  void a_month_pass_bought_during_a_year_pass_is_not_yet_active() {
    UUID y = UUID.randomUUID();
    PassState yearly = year(y);
    ProductGrant.Pass monthGrant = (ProductGrant.Pass) ProductGrant.of("PASS_MONTH");
    Instant starts = monthGrant.startsAt(NOW, java.util.Optional.of(yearly.endsAt()));
    PassState stacked =
        new PassState(UUID.randomUUID(), PassKind.MONTH, starts, monthGrant.endsAt(starts), 2, 0);

    assertThat(starts).isEqualTo(yearly.endsAt());
    Entitlements e = new Entitlements(0, List.of(yearly, stacked), List.of());
    assertThat(e.decide(NOW, VN)).isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, y));
    assertThat(e.activePass(NOW)).contains(yearly);
    assertThat(e.decide(yearly.endsAt(), VN))
        .isEqualTo(new ConsumeDecision.Use(CreditSource.PASS, stacked.id()));
  }

  @Test
  void questions_left() {
    assertThat(month(UUID.randomUUID(), 1, 3).questionsLeft()).isEqualTo(1);
    assertThat(year(UUID.randomUUID()).questionsLeft()).isNull();
  }

  @Test
  void topics_parse_by_ascii_name() {
    assertThat(Topic.valueOf("TinhDuyen")).isEqualTo(Topic.TinhDuyen);
    assertThat(Topic.values()).hasSize(6);
  }
}
