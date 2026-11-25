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
}
