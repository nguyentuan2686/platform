package dev.platform.reading.tuvi.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.application.qa.QaReading;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.application.qa.QaStatus;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The compare-and-set SQL of every status change, against real Postgres and V4. */
class JdbcQaRepositoryIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  static JdbcTemplate jdbc;

  final IdentitySubject subject = new IdentitySubject("account", UUID.randomUUID());
  JdbcQaRepository repo;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from qa_reading");
    repo = new JdbcQaRepository(jdbc);
  }

  UUID insert(QaStatus status) {
    UUID id = UUID.randomUUID();
    repo.insert(
        QaReading.create(
            id, subject, QaKind.PAID, QaTopic.TaiLoc, "vi", "h", QaStatus.PENDING, null, NOW),
        new NewMessage(0, id, new byte[] {1}, new byte[] {2}));
    if (status != QaStatus.PENDING) {
      jdbc.update("update qa_reading set status = ? where id = ?", status.name(), id);
    }
    return id;
  }

  QaStatus status(UUID id) {
    return QaStatus.valueOf(
        jdbc.queryForObject("select status from qa_reading where id = ?", String.class, id));
  }

  @Test
  void every_transition_follows_the_table() {
    for (QaStatus from : QaStatus.values()) {
      for (QaStatus to :
          List.of(
              QaStatus.ANSWERED,
              QaStatus.RETRYING,
              QaStatus.REFUND_PENDING,
              QaStatus.REFUSED,
              QaStatus.FAILED)) {
        UUID id = insert(from);
        boolean changed =
            switch (to) {
              case ANSWERED -> repo.markAnswered(id, NOW);
              case RETRYING -> repo.markRetrying(id, 1, NOW, NOW, "x");
              case REFUND_PENDING -> repo.markRefundPending(id, "x");
              case REFUSED -> repo.markRefused(id, RefusalReason.OFF_TOPIC);
              default -> repo.markFailed(id, "x");
            };
        assertThat(changed).as(from + " -> " + to).isEqualTo(from.canMoveTo(to));
        assertThat(status(id)).as(from + " -> " + to).isEqualTo(changed ? to : from);
      }
    }
  }

  @Test
  void answered_reading_is_not_overwritten() {
    UUID id = insert(QaStatus.PENDING);
    assertThat(repo.markAnswered(id, NOW)).isTrue();

    assertThat(repo.markRetrying(id, 1, NOW, NOW, "internal")).isFalse();
    assertThat(repo.markFailed(id, "internal")).isFalse();
    assertThat(repo.markRefundPending(id, "x")).isFalse();
    assertThat(repo.markAnswered(id, NOW)).isFalse();

    assertThat(status(id)).isEqualTo(QaStatus.ANSWERED);
    assertThat(
            jdbc.queryForObject("select last_error from qa_reading where id = ?", String.class, id))
        .isNull();
  }

  @Test
  void charge_only_on_pending_and_missing_rows_report_false() {
    UUID pending = insert(QaStatus.PENDING);
    UUID refused = insert(QaStatus.REFUSED);

    assertThat(repo.markCharged(pending, "CREDIT")).isTrue();
    assertThat(repo.markCharged(refused, "CREDIT")).isFalse();
    assertThat(repo.markCharged(UUID.randomUUID(), "CREDIT")).isFalse();
    assertThat(repo.markRetrying(UUID.randomUUID(), 1, NOW, NOW, "x")).isFalse();
    // a charged reading is never deleted as "uncharged"
    assertThat(repo.deleteUncharged(pending)).isFalse();
    assertThat(repo.find(pending)).isPresent();
  }

  @Test
  void refund_is_recorded_once_on_a_refused_reading() {
    UUID id = insert(QaStatus.REFUSED);

    assertThat(repo.recordRefund(id, NOW)).isTrue();
    assertThat(repo.recordRefund(id, NOW.plusSeconds(1))).isFalse();
    assertThat(repo.recordRefund(insert(QaStatus.ANSWERED), NOW)).isFalse();
  }

  @Test
  void history_delete_keeps_readings_still_owed_an_answer_or_refund() {
    UUID pending = insert(QaStatus.PENDING);
    UUID retrying = insert(QaStatus.RETRYING);
    UUID refundPending = insert(QaStatus.REFUND_PENDING);
    UUID answered = insert(QaStatus.ANSWERED);
    UUID refused = insert(QaStatus.REFUSED);
    repo.markCharged(pending, "CREDIT");

    assertThat(repo.anyInProgress(subject.id())).isTrue();
    assertThat(repo.deleteSettledBySubject(subject.id())).isEqualTo(2);

    assertThat(repo.find(pending)).isPresent();
    assertThat(repo.find(retrying)).isPresent();
    assertThat(repo.find(refundPending)).isPresent();
    assertThat(repo.find(answered)).isEmpty();
    assertThat(repo.find(refused)).isEmpty();
  }

  @Test
  void delete_during_pending_refund_of_paid_refusal_keeps_row_then_refund_pending() {
    UUID id = insert(QaStatus.PENDING);
    assertThat(repo.markCharged(id, "CREDIT")).isTrue();
    assertThat(repo.markRefused(id, RefusalReason.OFF_TOPIC)).isTrue();

    // the refund HTTP call is in flight: the reading is still owed its refund
    assertThat(repo.anyInProgress(subject.id())).isTrue();
    assertThat(repo.deleteSettledBySubject(subject.id())).isZero();
    assertThat(repo.find(id)).isPresent();

    // the refund failed: compensation is still possible
    assertThat(repo.markRefundPending(id, "refund_unavailable")).isTrue();
    assertThat(status(id)).isEqualTo(QaStatus.REFUND_PENDING);
    assertThat(repo.deleteSettledBySubject(subject.id())).isZero();
  }

  @Test
  void refunded_or_uncharged_refusals_are_settled() {
    UUID refunded = insert(QaStatus.PENDING);
    repo.markCharged(refunded, "CREDIT");
    repo.markRefused(refunded, RefusalReason.OFF_TOPIC);
    assertThat(repo.recordRefund(refunded, NOW)).isTrue();
    UUID guardRefused = insert(QaStatus.REFUSED); // never charged

    // a recorded refund is never moved back to pending
    assertThat(repo.markRefundPending(refunded, "x")).isFalse();
    assertThat(status(refunded)).isEqualTo(QaStatus.REFUSED);
    assertThat(repo.anyInProgress(subject.id())).isFalse();
    assertThat(repo.deleteSettledBySubject(subject.id())).isEqualTo(2);
    assertThat(repo.find(guardRefused)).isEmpty();
  }

  @Test
  void not_charged_refusal_is_settled_without_claiming_a_refund() {
    UUID id = insert(QaStatus.PENDING);
    repo.markCharged(id, "CREDIT");
    repo.markRefused(id, RefusalReason.OFF_TOPIC);
    assertThat(repo.anyInProgress(subject.id())).isTrue();

    assertThat(repo.recordNotCharged(id)).isTrue();

    QaReading r = repo.find(id).orElseThrow();
    assertThat(r.creditSource()).isNull();
    assertThat(r.refundedAt()).isNull();
    assertThat(r.lastError()).isEqualTo("refund:not_charged");
    assertThat(repo.anyInProgress(subject.id())).isFalse();
    assertThat(repo.recordNotCharged(insert(QaStatus.ANSWERED))).isFalse();
    assertThat(repo.deleteSettledBySubject(subject.id())).isEqualTo(2);
  }

  @Test
  void a_message_is_answered_once_and_an_answered_follow_up_is_never_deleted() {
    UUID id = insert(QaStatus.ANSWERED);
    repo.insertMessage(id, new NewMessage(1, UUID.randomUUID(), new byte[] {3}, null), NOW);

    assertThat(repo.answerMessage(id, 1, new byte[] {4}, "fake", List.of("k"), NOW)).isTrue();
    assertThat(repo.answerMessage(id, 1, new byte[] {5}, "fake", List.of(), NOW)).isFalse();
    assertThat(repo.deleteUnansweredMessage(id, 1)).isFalse();
    assertThat(repo.deleteUnansweredMessage(id, 0)).isFalse();
    assertThat(repo.messages(id)).hasSize(2);
    assertThat(repo.messages(id).get(1).answerEnc()).containsExactly(4);
  }

  // ---- jobs (Task 6) ----

  UUID insertAt(QaKind kind, QaStatus status, Instant created) {
    UUID id = UUID.randomUUID();
    repo.insert(
        QaReading.create(
            id, subject, kind, QaTopic.TaiLoc, "vi", "h", QaStatus.PENDING, null, created),
        new NewMessage(0, id, new byte[] {1}, new byte[] {2}));
    jdbc.update("update qa_reading set status = ? where id = ?", status.name(), id);
    return id;
  }

  byte[] prompt(UUID id) {
    return jdbc.queryForObject(
        "select prompt_enc from qa_message where reading_id = ? and seq = 0", byte[].class, id);
  }

  @Test
  void claim_due_leases_each_row_once() {
    UUID due = insert(QaStatus.PENDING);
    repo.markRetrying(due, 1, NOW, NOW.plusSeconds(86_400), "x");
    UUID later = insert(QaStatus.PENDING);
    repo.markRetrying(later, 1, NOW.plusSeconds(60), NOW.plusSeconds(86_400), "x");
    Instant lease = NOW.plusSeconds(600);

    List<QaReading> first = repo.claimDue(NOW, lease, 10);
    List<QaReading> second = repo.claimDue(NOW, lease, 10);

    assertThat(first).extracting(QaReading::id).containsExactly(due);
    assertThat(first.getFirst().nextAttemptAt()).isEqualTo(lease);
    assertThat(first.getFirst().attempts()).isEqualTo(1);
    assertThat(second).isEmpty();
    assertThat(repo.claimDue(lease, lease.plusSeconds(600), 10)).hasSize(2);
  }

  @Test
  void claim_refunds_takes_pending_refunds_and_honours_the_lease() {
    UUID a = insert(QaStatus.PENDING);
    assertThat(repo.markRefundPending(a, "consume_unavailable")).isTrue();
    insert(QaStatus.RETRYING);

    List<QaReading> claimed = repo.claimRefunds(NOW, NOW.plusSeconds(60), 10);

    assertThat(claimed).extracting(QaReading::id).containsExactly(a);
    assertThat(repo.claimRefunds(NOW.plusSeconds(30), NOW.plusSeconds(90), 10)).isEmpty();
    assertThat(repo.claimRefunds(NOW.plusSeconds(60), NOW.plusSeconds(120), 10)).hasSize(1);
  }

  @Test
  void mark_refund_pending_clears_a_retry_lease() {
    UUID id = insert(QaStatus.PENDING);
    repo.markRetrying(id, 3, NOW.plusSeconds(600), NOW, "x");

    assertThat(repo.markRefundPending(id, "retry_window_elapsed")).isTrue();

    assertThat(repo.find(id).orElseThrow().nextAttemptAt()).isNull();
    assertThat(repo.claimRefunds(NOW, NOW.plusSeconds(60), 10)).hasSize(1);
  }

  @Test
  void refunded_and_failed_drop_the_prompt() {
    UUID refunded = insert(QaStatus.REFUND_PENDING);
    UUID failed = insert(QaStatus.REFUND_PENDING);

    assertThat(repo.markRefunded(refunded, NOW)).isTrue();
    assertThat(repo.markRefunded(refunded, NOW)).isFalse();
    assertThat(repo.markFailed(failed, "refund:not_charged")).isTrue();

    QaReading r = repo.find(refunded).orElseThrow();
    assertThat(r.status()).isEqualTo(QaStatus.REFUNDED);
    assertThat(r.refundedAt()).isEqualTo(NOW);
    assertThat(prompt(refunded)).isNull();
    assertThat(prompt(failed)).isNull();
    assertThat(repo.markRefunded(insert(QaStatus.RETRYING), NOW)).isFalse();
  }

  @Test
  void requeue_only_charged_stale_pending() {
    Instant old = NOW.minusSeconds(660);
    UUID charged = insertAt(QaKind.PAID, QaStatus.PENDING, old);
    repo.markCharged(charged, "CREDIT");
    UUID uncharged = insertAt(QaKind.PAID, QaStatus.PENDING, old);
    UUID fresh = insertAt(QaKind.PAID, QaStatus.PENDING, NOW);
    repo.markCharged(fresh, "CREDIT");

    int n = repo.requeueStalePending(NOW.minusSeconds(600), NOW, java.time.Duration.ofHours(24));

    assertThat(n).isEqualTo(1);
    QaReading r = repo.find(charged).orElseThrow();
    assertThat(r.status()).isEqualTo(QaStatus.RETRYING);
    assertThat(r.nextAttemptAt()).isEqualTo(NOW);
    assertThat(r.retryUntil()).isEqualTo(old.plusSeconds(86_400));
    assertThat(r.attempts()).isEqualTo(1);
    assertThat(r.lastError()).isEqualTo("stale_pending");
    assertThat(status(uncharged)).isEqualTo(QaStatus.PENDING);
    assertThat(status(fresh)).isEqualTo(QaStatus.PENDING);
    assertThat(repo.staleUncharged(NOW.minusSeconds(600), 10))
        .extracting(QaReading::id)
        .containsExactly(uncharged);
  }

  @Test
  void unrefunded_refusals_finds_only_owed_old_refusals() {
    Instant old = NOW.minusSeconds(660);
    UUID owed = insertAt(QaKind.PAID, QaStatus.PENDING, old);
    repo.markCharged(owed, "CREDIT");
    repo.markRefused(owed, RefusalReason.OFF_TOPIC);
    UUID refunded = insertAt(QaKind.PAID, QaStatus.PENDING, old);
    repo.markCharged(refunded, "CREDIT");
    repo.markRefused(refunded, RefusalReason.OFF_TOPIC);
    repo.recordRefund(refunded, NOW);
    insertAt(QaKind.PAID, QaStatus.REFUSED, old); // guard refusal, never charged
    UUID fresh = insert(QaStatus.PENDING);
    repo.markCharged(fresh, "CREDIT");
    repo.markRefused(fresh, RefusalReason.OFF_TOPIC);

    assertThat(repo.unrefundedRefusals(NOW.minusSeconds(600), 10)).containsExactly(owed);
  }

  @Test
  void purge_free_keeps_paid_recent_and_owed() {
    jdbc.update("delete from qa_answer_cache");
    Instant old = NOW.minusSeconds(91L * 86_400);
    UUID oldFree = insertAt(QaKind.FREE, QaStatus.ANSWERED, old);
    UUID oldPending = insertAt(QaKind.FREE, QaStatus.PENDING, old);
    UUID recentFree = insertAt(QaKind.FREE, QaStatus.ANSWERED, NOW.minusSeconds(89L * 86_400));
    UUID oldPaid = insertAt(QaKind.PAID, QaStatus.ANSWERED, NOW.minusSeconds(400L * 86_400));
    jdbc.update(
        "insert into qa_answer_cache (cache_key, answer_enc, created_at) values"
            + " ('old', ?, ?), ('new', ?, ?)",
        new byte[] {1},
        java.sql.Timestamp.from(old),
        new byte[] {1},
        java.sql.Timestamp.from(NOW));

    Instant before = NOW.minusSeconds(90L * 86_400);
    assertThat(repo.purgeFree(before)).isEqualTo(1);
    assertThat(repo.purgeCache(before)).isEqualTo(1);

    assertThat(repo.find(oldFree)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from qa_message where reading_id = ?", Integer.class, oldFree))
        .isZero();
    assertThat(repo.find(oldPending)).isPresent();
    assertThat(repo.find(recentFree)).isPresent();
    assertThat(repo.find(oldPaid)).isPresent();
    assertThat(jdbc.queryForList("select cache_key from qa_answer_cache", String.class))
        .containsExactly("new");
  }
}
