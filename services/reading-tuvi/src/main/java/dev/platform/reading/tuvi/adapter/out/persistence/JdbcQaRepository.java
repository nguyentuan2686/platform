package dev.platform.reading.tuvi.adapter.out.persistence;

import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.application.qa.QaReading;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort;
import dev.platform.reading.tuvi.application.qa.QaStatus;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** {@code qa_reading} and {@code qa_message}; joins the caller's transaction when there is one. */
@Repository
public class JdbcQaRepository implements QaRepositoryPort {

  private static final String READING_COLUMNS =
      "id, subject_kind, subject_id, kind, topic, lang, chart_hash, status, refusal_reason,"
          + " credit_source, attempts, next_attempt_at, retry_until, last_error, created_at,"
          + " answered_at, refunded_at";
  private static final String IN_PROGRESS = list(QaStatus.IN_PROGRESS);
  private static final String COUNTED_FREE =
      " kind = 'FREE' and status in ('PENDING', 'ANSWERED') and created_at >= ?";

  private static final RowMapper<QaReading> READING =
      (rs, n) ->
          new QaReading(
              rs.getObject("id", UUID.class),
              rs.getString("subject_kind"),
              rs.getObject("subject_id", UUID.class),
              QaKind.valueOf(rs.getString("kind")),
              QaTopic.valueOf(rs.getString("topic")),
              rs.getString("lang"),
              rs.getString("chart_hash"),
              QaStatus.valueOf(rs.getString("status")),
              rs.getString("refusal_reason"),
              rs.getString("credit_source"),
              rs.getInt("attempts"),
              instant(rs, "next_attempt_at"),
              instant(rs, "retry_until"),
              rs.getString("last_error"),
              instant(rs, "created_at"),
              instant(rs, "answered_at"),
              instant(rs, "refunded_at"));

  private static final RowMapper<StoredMessage> MESSAGE =
      (rs, n) ->
          new StoredMessage(
              rs.getObject("reading_id", UUID.class),
              rs.getInt("seq"),
              rs.getObject("client_message_id", UUID.class),
              rs.getBytes("question_enc"),
              rs.getBytes("answer_enc"),
              rs.getBytes("prompt_enc"),
              instant(rs, "created_at"),
              instant(rs, "answered_at"));

  private static final String MESSAGE_COLUMNS =
      "reading_id, seq, client_message_id, question_enc, answer_enc, prompt_enc, created_at,"
          + " answered_at";

  private final JdbcTemplate jdbc;

  public JdbcQaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(QaReading r, NewMessage first) {
    int rows =
        jdbc.update(
            "insert into qa_reading (id, subject_kind, subject_id, kind, topic, lang, chart_hash,"
                + " status, refusal_reason, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " on conflict (id) do nothing",
            r.id(),
            r.subjectKind(),
            r.subjectId(),
            r.kind().name(),
            r.topic().name(),
            r.lang(),
            r.chartHash(),
            r.status().name(),
            r.refusalReason(),
            ts(r.createdAt()));
    if (rows == 0) {
      return false;
    }
    if (!insertMessage(r.id(), first, r.createdAt())) {
      throw new IllegalStateException("first message id already used");
    }
    return true;
  }

  @Override
  public Optional<QaReading> find(UUID id) {
    return jdbc
        .query("select " + READING_COLUMNS + " from qa_reading where id = ?", READING, id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<QaReading> findForUpdate(UUID id) {
    return jdbc
        .query(
            "select " + READING_COLUMNS + " from qa_reading where id = ? for update", READING, id)
        .stream()
        .findFirst();
  }

  @Override
  public boolean deleteUncharged(UUID id) {
    return jdbc.update(
            "delete from qa_reading where id = ? and status = 'PENDING' and credit_source is null",
            id)
        == 1;
  }

  /** {@code and status in (...)} for the statuses a reading may move to {@code target} from. */
  private static String from(QaStatus target) {
    return " and status in " + list(target.allowedFrom());
  }

  private static String list(Set<QaStatus> statuses) {
    return statuses.stream()
        .map(s -> "'" + s.name() + "'")
        .collect(Collectors.joining(", ", "(", ")"));
  }

  @Override
  public boolean markCharged(UUID id, String creditSource) {
    return jdbc.update(
            "update qa_reading set credit_source = ? where id = ? and status = 'PENDING'",
            creditSource,
            id)
        == 1;
  }

  @Override
  public boolean markRefundPending(UUID id, String lastError) {
    return jdbc.update(
            "update qa_reading set status = 'REFUND_PENDING', last_error = ? where id = ?"
                + from(QaStatus.REFUND_PENDING),
            lastError,
            id)
        == 1;
  }

  @Override
  public boolean markRefused(UUID id, RefusalReason reason) {
    boolean changed =
        jdbc.update(
                "update qa_reading set status = 'REFUSED', refusal_reason = ? where id = ?"
                    + from(QaStatus.REFUSED),
                reason.name(),
                id)
            == 1;
    if (changed) {
      jdbc.update("update qa_message set prompt_enc = null where reading_id = ?", id);
    }
    return changed;
  }

  @Override
  public boolean recordRefund(UUID id, Instant at) {
    return jdbc.update(
            "update qa_reading set refunded_at = ? where id = ? and status = 'REFUSED'"
                + " and refunded_at is null",
            ts(at),
            id)
        == 1;
  }

  @Override
  public boolean markRetrying(
      UUID id, int attempts, Instant nextAttemptAt, Instant retryUntil, String lastError) {
    return jdbc.update(
            "update qa_reading set status = 'RETRYING', attempts = ?, next_attempt_at = ?,"
                + " retry_until = ?, last_error = ? where id = ?"
                + from(QaStatus.RETRYING),
            attempts,
            ts(nextAttemptAt),
            ts(retryUntil),
            lastError,
            id)
        == 1;
  }

  @Override
  public boolean markFailed(UUID id, String lastError) {
    boolean changed =
        jdbc.update(
                "update qa_reading set status = 'FAILED', last_error = ? where id = ?"
                    + from(QaStatus.FAILED),
                lastError,
                id)
            == 1;
    if (changed) {
      jdbc.update("update qa_message set prompt_enc = null where reading_id = ?", id);
    }
    return changed;
  }

  @Override
  public boolean markAnswered(UUID id, Instant at) {
    return jdbc.update(
            "update qa_reading set status = 'ANSWERED', answered_at = ?, next_attempt_at = null"
                + " where id = ?"
                + from(QaStatus.ANSWERED),
            ts(at),
            id)
        == 1;
  }

  @Override
  public boolean answerMessage(
      UUID readingId,
      int seq,
      byte[] answerEnc,
      String provider,
      List<String> usedKeys,
      Instant at) {
    return jdbc.update(
            con -> {
              var ps =
                  con.prepareStatement(
                      "update qa_message set answer_enc = ?, provider = ?, used_keys = ?,"
                          + " answered_at = ?, prompt_enc = null"
                          + " where reading_id = ? and seq = ? and answer_enc is null");
              ps.setBytes(1, answerEnc);
              ps.setString(2, provider);
              ps.setArray(3, con.createArrayOf("text", usedKeys.toArray()));
              ps.setTimestamp(4, ts(at));
              ps.setObject(5, readingId);
              ps.setInt(6, seq);
              return ps;
            })
        == 1;
  }

  @Override
  public List<StoredMessage> messages(UUID readingId) {
    return jdbc.query(
        "select " + MESSAGE_COLUMNS + " from qa_message where reading_id = ? order by seq",
        MESSAGE,
        readingId);
  }

  @Override
  public Optional<StoredMessage> messageByClientId(UUID clientMessageId) {
    return jdbc
        .query(
            "select " + MESSAGE_COLUMNS + " from qa_message where client_message_id = ?",
            MESSAGE,
            clientMessageId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean insertMessage(UUID readingId, NewMessage m, Instant at) {
    return jdbc.update(
            "insert into qa_message (reading_id, seq, client_message_id, question_enc,"
                + " prompt_enc, created_at) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
            readingId,
            m.seq(),
            m.clientMessageId(),
            m.questionEnc(),
            m.promptEnc(),
            ts(at))
        == 1;
  }

  @Override
  public boolean deleteUnansweredMessage(UUID readingId, int seq) {
    return jdbc.update(
            "delete from qa_message where reading_id = ? and seq = ? and seq > 0"
                + " and answer_enc is null",
            readingId,
            seq)
        == 1;
  }

  @Override
  public int countFreeBySubject(UUID subjectId, Instant since) {
    return count("subject_id = ? and" + COUNTED_FREE, subjectId, ts(since));
  }

  @Override
  public int countFreeByChart(String chartHash, Instant since) {
    return count("chart_hash = ? and" + COUNTED_FREE, chartHash, ts(since));
  }

  @Override
  public int countFreeGlobal(Instant since) {
    return count(COUNTED_FREE, ts(since));
  }

  private int count(String where, Object... args) {
    Integer n =
        jdbc.queryForObject("select count(*) from qa_reading where " + where, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Override
  public void lockChart(String chartHash) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtext(?))", (RowCallbackHandler) rs -> {}, chartHash);
  }

  @Override
  public List<Listed> listBySubject(UUID subjectId, int limit) {
    return jdbc.query(
        "select "
            + READING_COLUMNS
            + ", (select count(*) from qa_message m where m.reading_id = r.id and m.seq > 0)"
            + " as follow_ups from qa_reading r where subject_id = ?"
            + " order by created_at desc limit ?",
        (rs, n) -> new Listed(READING.mapRow(rs, n), rs.getInt("follow_ups")),
        subjectId,
        limit);
  }

  @Override
  public int countFollowUps(UUID readingId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from qa_message where reading_id = ? and seq > 0",
            Integer.class,
            readingId);
    return n == null ? 0 : n;
  }

  @Override
  public boolean anyInProgress(UUID subjectId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from qa_reading where subject_id = ? and status in "
                + IN_PROGRESS
                + ")",
            Boolean.class,
            subjectId));
  }

  @Override
  public int deleteSettledBySubject(UUID subjectId) {
    return jdbc.update(
        "delete from qa_reading where subject_id = ? and status not in " + IN_PROGRESS, subjectId);
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
