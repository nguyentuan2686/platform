package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.DailyResult;
import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmProviderPort;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmUnavailableException;
import dev.platform.reading.tuvi.application.llm.LlmUsagePort;
import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.ChartFixture;
import dev.platform.reading.tuvi.domain.DailyFacts;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory fakes for every port used by {@link QaService}. */
final class QaFakes {

  private QaFakes() {}

  static final String GOOD =
      "Con hỏi về chuyện này, ta xem lá số của con thì thấy cung Phu Thê có sao tốt chiếu."
          + " Con cứ bình tâm mà làm, việc gì cũng có thời của nó."
          + " Điều nào hợp với con thì con giữ lấy, điều nào chưa hợp thì con cứ để đó mà ngẫm"
          + " thêm.";
  static final String UNSAFE = GOOD + " Con nên đi giải hạn ngay.";

  /** A clock the test can move. */
  static final class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static final class FakeIdentity implements IdentityPort {
    final Map<String, IdentitySubject> subjects = new HashMap<>();
    final Map<String, Birth> profiles = new HashMap<>();
    final List<String> consumed = new ArrayList<>();
    final List<UUID> refunds = new ArrayList<>();
    Supplier<ConsumeOutcome> consume = () -> new ConsumeOutcome.Charged("CREDIT");
    Supplier<RefundOutcome> refund = () -> RefundOutcome.REFUNDED;

    IdentitySubject add(String bearer, String kind) {
      IdentitySubject s = new IdentitySubject(kind, UUID.randomUUID());
      subjects.put(bearer, s);
      profiles.put(bearer, new Birth("1994-03-12T08:30", "Asia/Ho_Chi_Minh", "male", "VN"));
      return s;
    }

    @Override
    public IdentitySubject whoAmI(String bearer) {
      IdentitySubject s = subjects.get(bearer);
      if (s == null) {
        throw new UnauthenticatedException();
      }
      return s;
    }

    @Override
    public Optional<Birth> profile(String bearer) {
      return Optional.ofNullable(profiles.get(bearer));
    }

    @Override
    public ConsumeOutcome consume(UUID accountId, UUID readingId, QaTopic topic) {
      consumed.add(readingId + ":" + topic);
      return consume.get();
    }

    @Override
    public RefundOutcome refund(UUID readingId) {
      RefundOutcome out = refund.get();
      refunds.add(readingId);
      return out;
    }
  }

  static final class FakeCharts implements ChartPort {
    final Chart chart = ChartFixture.load();
    String hash = "hash-A";
    boolean down;
    int dailyCalls;

    @Override
    public ChartResult chart(Birth birth) {
      if (down) {
        throw new UpstreamUnavailableException("chart");
      }
      return new ChartResult(chart, hash, "school-x");
    }

    @Override
    public DailyResult daily(Birth birth, LocalDate date) {
      dailyCalls++;
      FlowLayer layer = new FlowLayer("QuanLoc", "Zi", "Jia", List.of());
      return new DailyResult(new DailyFacts(layer, layer, layer), "1/1", "Giáp Tý");
    }
  }

  static final class FakeKnowledge implements KnowledgePort {
    final Map<String, String> frames = new HashMap<>();
    boolean failFrames;

    FakeKnowledge() {
      frames.put("qa_disclaimer", "LỜI-NHẮC");
      frames.put("qa_queued", "ĐANG-NGẪM");
      frames.put("qa_busy", "MỎI-RỒI");
      frames.put("qa_refunded", "ĐÃ-TRẢ");
      frames.put("qa_insufficient", "CHƯA-ĐỦ");
      frames.put("qa_refuse_offtopic", "NGOÀI-LỀ");
      for (String r : List.of("medical", "legal", "finance", "gambling", "death", "selfharm")) {
        frames.put("qa_refuse_" + r, "TỪ-CHỐI-" + r);
      }
    }

    @Override
    public Optional<String> find(KnowledgeKey key, String lang) {
      String v = key.value();
      if (failFrames && v.startsWith("tuvi:frame=")) {
        throw new IllegalStateException("knowledge down");
      }
      if (v.startsWith("tuvi:frame=")) {
        return Optional.ofNullable(frames.get(v.substring("tuvi:frame=".length())));
      }
      // every palace overview has a passage
      if (v.matches("tuvi:palace=[A-Za-z]+")) {
        return Optional.of("Tư liệu về " + v.substring(12) + ".");
      }
      return Optional.empty();
    }

    @Override
    public long version() {
      return 7;
    }
  }

  /** A provider answering from a script, then with {@link #fallback}. */
  static final class ScriptedProvider implements LlmProviderPort {
    final Deque<Supplier<String>> script = new ArrayDeque<>();
    final List<LlmRequest> requests = new ArrayList<>();
    Supplier<String> fallback = () -> GOOD;

    void then(String text) {
      script.add(() -> text);
    }

    void thenFail() {
      script.add(
          () -> {
            throw new LlmUnavailableException("down");
          });
    }

    @Override
    public String id() {
      return "scripted";
    }

    @Override
    public boolean paid() {
      return false;
    }

    @Override
    public int dailyRequestLimit() {
      return Integer.MAX_VALUE;
    }

    @Override
    public LlmCompletion complete(LlmRequest request) {
      requests.add(request);
      Supplier<String> next = script.isEmpty() ? fallback : script.poll();
      return new LlmCompletion(next.get(), id(), 10, 10);
    }
  }

  static final class MemoryUsage implements LlmUsagePort {
    @Override
    public int requestsOn(String provider, LocalDate utcDay) {
      return 0;
    }

    @Override
    public void recordAttempt(String provider, LocalDate utcDay) {}

    @Override
    public void recordTokens(
        String provider, LocalDate utcDay, int prompt, int completion, long costMicroUsd) {}

    @Override
    public long spendMicroUsd(String provider, YearMonth utcMonth) {
      return 0;
    }

    @Override
    public void markExhausted(String provider, Instant until) {}

    @Override
    public Optional<Instant> exhaustedUntil(String provider) {
      return Optional.empty();
    }
  }

  /** Not real crypto: XOR with a fixed byte, AAD prefixed and checked on decrypt. */
  static final class FakeCipher implements QaTextCipherPort {
    @Override
    public byte[] encrypt(String aad, String text) {
      byte[] b = (aad + "\u0000" + text).getBytes(StandardCharsets.UTF_8);
      for (int i = 0; i < b.length; i++) {
        b[i] ^= 0x5A;
      }
      return b;
    }

    @Override
    public String decrypt(String aad, byte[] data) {
      byte[] b = Arrays.copyOf(data, data.length);
      for (int i = 0; i < b.length; i++) {
        b[i] ^= 0x5A;
      }
      String s = new String(b, StandardCharsets.UTF_8);
      if (!s.startsWith(aad + "\u0000")) {
        throw new IllegalStateException("aad mismatch");
      }
      return s.substring(aad.length() + 1);
    }
  }

  static final class MemoryCache implements QaAnswerCachePort {
    final Map<String, byte[]> entries = new HashMap<>();

    @Override
    public Optional<byte[]> find(String cacheKey) {
      return Optional.ofNullable(entries.get(cacheKey));
    }

    @Override
    public void put(String cacheKey, byte[] answerEnc) {
      entries.putIfAbsent(cacheKey, answerEnc);
    }
  }

  static final class MemoryRepository implements QaRepositoryPort {
    final Map<UUID, QaReading> readings = new LinkedHashMap<>();
    final List<StoredMessage> messages = new ArrayList<>();
    final Map<String, String> providers = new HashMap<>();
    final List<String> locks = new ArrayList<>();

    QaReading reading(UUID id) {
      return readings.get(id);
    }

    @Override
    public synchronized boolean insert(QaReading reading, NewMessage first) {
      if (readings.containsKey(reading.id())) {
        return false;
      }
      readings.put(reading.id(), reading);
      insertMessage(reading.id(), first, reading.createdAt());
      return true;
    }

    @Override
    public Optional<QaReading> find(UUID id) {
      return Optional.ofNullable(readings.get(id));
    }

    @Override
    public Optional<QaReading> findForUpdate(UUID id) {
      locks.add("reading:" + id);
      return find(id);
    }

    /** Hook run before each status change (to simulate a concurrent writer). */
    Runnable beforeTransition = () -> {};

    void remove(UUID id) {
      readings.remove(id);
      messages.removeIf(m -> m.readingId().equals(id));
    }

    @Override
    public synchronized boolean deleteUncharged(UUID id) {
      QaReading r = readings.get(id);
      if (r == null || r.status() != QaStatus.PENDING || r.creditSource() != null) {
        return false;
      }
      remove(id);
      return true;
    }

    /** Compare-and-set to {@code target}, exactly as the JDBC adapter does. */
    private synchronized boolean transition(
        UUID id, QaStatus target, java.util.function.UnaryOperator<QaReading> f) {
      beforeTransition.run();
      QaReading r = readings.get(id);
      if (r == null || !target.allowedFrom().contains(r.status())) {
        return false;
      }
      readings.put(id, f.apply(r));
      claims.remove(id);
      return true;
    }

    /** Job claim tokens by reading (the claim_token column). */
    final Map<UUID, UUID> claims = new HashMap<>();

    /** A write by the holder of {@code claim}: applies only while the claim is current. */
    private synchronized boolean claimed(
        UUID id, UUID claim, java.util.function.BooleanSupplier write) {
      if (!claim.equals(claims.get(id))) {
        return false;
      }
      return write.getAsBoolean();
    }

    @Override
    public synchronized boolean markCharged(UUID id, String creditSource) {
      beforeTransition.run();
      QaReading r = readings.get(id);
      if (r == null || r.status() != QaStatus.PENDING) {
        return false;
      }
      readings.put(id, r.withCreditSource(creditSource));
      return true;
    }

    @Override
    public boolean markRefundPending(UUID id, String lastError) {
      return markRefundPending(id, lastError, null);
    }

    @Override
    public boolean markRefundPendingClaimed(UUID id, UUID claim, String lastError) {
      return claimed(id, claim, () -> markRefundPending(id, lastError));
    }

    @Override
    public boolean markRetryingClaimed(
        UUID id, UUID claim, int attempts, Instant next, Instant until, String lastError) {
      return claimed(id, claim, () -> markRetrying(id, attempts, next, until, lastError));
    }

    @Override
    public boolean markFailedClaimed(UUID id, UUID claim, String lastError) {
      return claimed(id, claim, () -> markFailed(id, lastError));
    }

    @Override
    public boolean markAnsweredClaimed(UUID id, UUID claim, Instant at) {
      return claimed(id, claim, () -> markAnswered(id, at));
    }

    @Override
    public boolean markRefundPending(UUID id, String lastError, Instant notBefore) {
      QaReading current = readings.get(id);
      if (current != null && current.status() == QaStatus.REFUSED && current.refundedAt() != null) {
        return false;
      }
      boolean ok =
          transition(
              id,
              QaStatus.REFUND_PENDING,
              r ->
                  withNext(
                      r.withStatus(QaStatus.REFUND_PENDING).withLastError(lastError), notBefore));
      if (ok) {
        clearPrompt(id, 0);
      }
      return ok;
    }

    @Override
    public boolean markRefused(UUID id, RefusalReason reason) {
      boolean ok =
          transition(
              id, QaStatus.REFUSED, r -> r.withStatus(QaStatus.REFUSED).withRefusal(reason.name()));
      if (ok) {
        clearPrompt(id, 0);
      }
      return ok;
    }

    @Override
    public synchronized boolean recordRefund(UUID id, Instant at) {
      QaReading r = readings.get(id);
      if (r == null || r.status() != QaStatus.REFUSED || r.refundedAt() != null) {
        return false;
      }
      readings.put(id, r.withRefundedAt(at));
      return true;
    }

    @Override
    public synchronized boolean recordNotCharged(UUID id) {
      QaReading r = readings.get(id);
      if (r == null || r.status() != QaStatus.REFUSED || r.refundedAt() != null) {
        return false;
      }
      readings.put(id, r.withCreditSource(null).withLastError("refund:not_charged"));
      return true;
    }

    @Override
    public boolean markRetrying(
        UUID id, int attempts, Instant nextAttemptAt, Instant retryUntil, String lastError) {
      return transition(
          id, QaStatus.RETRYING, r -> r.withRetry(attempts, nextAttemptAt, retryUntil, lastError));
    }

    @Override
    public boolean markFailed(UUID id, String lastError) {
      boolean ok =
          transition(
              id, QaStatus.FAILED, r -> r.withStatus(QaStatus.FAILED).withLastError(lastError));
      if (ok) {
        clearPrompt(id, 0);
      }
      return ok;
    }

    @Override
    public boolean markAnswered(UUID id, Instant at) {
      return transition(
          id, QaStatus.ANSWERED, r -> r.withStatus(QaStatus.ANSWERED).withAnsweredAt(at));
    }

    @Override
    public synchronized boolean answerMessage(
        UUID readingId,
        int seq,
        byte[] answerEnc,
        String provider,
        List<String> usedKeys,
        Instant at) {
      for (int i = 0; i < messages.size(); i++) {
        StoredMessage m = messages.get(i);
        if (m.readingId().equals(readingId) && m.seq() == seq && m.answerEnc() == null) {
          messages.set(
              i,
              new StoredMessage(
                  readingId,
                  seq,
                  m.clientMessageId(),
                  m.questionEnc(),
                  answerEnc,
                  null,
                  m.createdAt(),
                  at));
          providers.put(readingId + ":" + seq, provider);
          return true;
        }
      }
      return false;
    }

    private void clearPrompt(UUID readingId, int seq) {
      for (int i = 0; i < messages.size(); i++) {
        StoredMessage m = messages.get(i);
        if (m.readingId().equals(readingId) && m.seq() == seq) {
          messages.set(
              i,
              new StoredMessage(
                  readingId,
                  seq,
                  m.clientMessageId(),
                  m.questionEnc(),
                  m.answerEnc(),
                  null,
                  m.createdAt(),
                  m.answeredAt()));
        }
      }
    }

    @Override
    public List<StoredMessage> messages(UUID readingId) {
      return messages.stream()
          .filter(m -> m.readingId().equals(readingId))
          .sorted(Comparator.comparingInt(StoredMessage::seq))
          .toList();
    }

    @Override
    public Optional<StoredMessage> messageByClientId(UUID clientMessageId) {
      return messages.stream().filter(m -> m.clientMessageId().equals(clientMessageId)).findFirst();
    }

    @Override
    public boolean insertMessage(UUID readingId, NewMessage message, Instant at) {
      if (messageByClientId(message.clientMessageId()).isPresent()) {
        return false;
      }
      messages.add(
          new StoredMessage(
              readingId,
              message.seq(),
              message.clientMessageId(),
              message.questionEnc(),
              null,
              message.promptEnc(),
              at,
              null));
      return true;
    }

    @Override
    public synchronized boolean deleteUnansweredMessage(UUID readingId, int seq) {
      return messages.removeIf(
          m ->
              m.readingId().equals(readingId)
                  && m.seq() == seq
                  && seq > 0
                  && m.answerEnc() == null);
    }

    /** As the JDBC adapter: reached the model (model refusals have a chart hash). */
    private boolean countedFree(QaReading r, Instant since) {
      return r.kind() == QaKind.FREE
          && (r.status() == QaStatus.PENDING
              || r.status() == QaStatus.ANSWERED
              || (r.status() == QaStatus.REFUSED && !r.chartHash().isEmpty()))
          && !r.createdAt().isBefore(since);
    }

    @Override
    public int countFreeBySubject(UUID subjectId, Instant since) {
      return (int)
          readings.values().stream()
              .filter(r -> r.subjectId().equals(subjectId) && countedFree(r, since))
              .count();
    }

    @Override
    public int countFreeByChart(String chartHash, Instant since) {
      return (int)
          readings.values().stream()
              .filter(r -> r.chartHash().equals(chartHash) && countedFree(r, since))
              .count();
    }

    @Override
    public int countFreeGlobal(Instant since) {
      return (int)
          readings.values().stream()
              .filter(
                  r ->
                      countedFree(r, since)
                          || (r.kind() == QaKind.FREE
                              && r.status() == QaStatus.FAILED
                              && !r.createdAt().isBefore(since)))
              .count();
    }

    @Override
    public void lockChart(String chartHash) {
      locks.add("chart:" + chartHash);
    }

    @Override
    public List<Listed> listBySubject(UUID subjectId, int limit) {
      return readings.values().stream()
          .filter(r -> r.subjectId().equals(subjectId))
          .sorted(Comparator.comparing(QaReading::createdAt).reversed())
          .limit(limit)
          .map(r -> new Listed(r, countFollowUps(r.id())))
          .toList();
    }

    @Override
    public int countFollowUps(UUID readingId) {
      return (int) messages(readingId).stream().filter(m -> m.seq() > 0).count();
    }

    @Override
    public boolean anyInProgress(UUID subjectId) {
      return readings.values().stream().anyMatch(r -> r.subjectId().equals(subjectId) && r.owed());
    }

    @Override
    public synchronized int deleteSettledBySubject(UUID subjectId) {
      List<UUID> ids =
          readings.values().stream()
              .filter(r -> r.subjectId().equals(subjectId) && !r.owed())
              .map(QaReading::id)
              .toList();
      ids.stream().filter(id -> readings.get(id).kind() == QaKind.PAID).forEach(tombstones::add);
      ids.forEach(this::remove);
      return ids.size();
    }

    /** Deleted PAID reading ids (qa_deleted_reading). */
    final java.util.Set<UUID> tombstones = new java.util.HashSet<>();

    @Override
    public boolean deletedPaid(UUID id) {
      return tombstones.contains(id);
    }

    // ---- jobs (Task 6) ----

    Instant purgedCacheBefore;

    private QaReading withNext(QaReading r, Instant next) {
      return new QaReading(
          r.id(),
          r.subjectKind(),
          r.subjectId(),
          r.kind(),
          r.topic(),
          r.lang(),
          r.chartHash(),
          r.status(),
          r.refusalReason(),
          r.creditSource(),
          r.attempts(),
          next,
          r.retryUntil(),
          r.lastError(),
          r.createdAt(),
          r.answeredAt(),
          r.refundedAt());
    }

    private synchronized Optional<QaReading> claim(
        java.util.function.Predicate<QaReading> due, Instant leaseUntil, UUID claim) {
      Optional<QaReading> next =
          readings.values().stream()
              .filter(due)
              .min(Comparator.comparing(QaReading::createdAt))
              .map(r -> withNext(r, leaseUntil));
      next.ifPresent(
          r -> {
            readings.put(r.id(), r);
            claims.put(r.id(), claim);
          });
      return next;
    }

    @Override
    public Optional<QaReading> claimNextDue(Instant now, Instant leaseUntil, UUID claim) {
      return claim(
          r ->
              r.status() == QaStatus.RETRYING
                  && r.nextAttemptAt() != null
                  && !r.nextAttemptAt().isAfter(now),
          leaseUntil,
          claim);
    }

    @Override
    public Optional<QaReading> claimNextRefund(Instant now, Instant leaseUntil, UUID claim) {
      return claim(
          r ->
              r.status() == QaStatus.REFUND_PENDING
                  && (r.nextAttemptAt() == null || !r.nextAttemptAt().isAfter(now)),
          leaseUntil,
          claim);
    }

    @Override
    public synchronized int requeueStalePending(
        Instant olderThan, Instant now, java.time.Duration retryWindow) {
      List<QaReading> stale =
          readings.values().stream()
              .filter(
                  r ->
                      r.kind() == QaKind.PAID
                          && r.status() == QaStatus.PENDING
                          && r.creditSource() != null
                          && r.createdAt().isBefore(olderThan))
              .toList();
      for (QaReading r : stale) {
        readings.put(
            r.id(),
            r.withRetry(
                Math.max(r.attempts(), 1),
                now,
                r.retryUntil() != null ? r.retryUntil() : r.createdAt().plus(retryWindow),
                "stale_pending"));
      }
      return stale.size();
    }

    @Override
    public List<UUID> unrefundedRefusals(Instant olderThan, int limit) {
      return readings.values().stream()
          .filter(
              r ->
                  r.kind() == QaKind.PAID
                      && r.status() == QaStatus.REFUSED
                      && r.creditSource() != null
                      && r.refundedAt() == null
                      && r.createdAt().isBefore(olderThan))
          .limit(limit)
          .map(QaReading::id)
          .toList();
    }

    @Override
    public List<QaReading> staleUncharged(Instant olderThan, int limit) {
      return readings.values().stream()
          .filter(
              r ->
                  r.status() == QaStatus.PENDING
                      && r.creditSource() == null
                      && r.createdAt().isBefore(olderThan))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean markRefunded(UUID id, UUID claim, Instant at) {
      if (!claim.equals(claims.get(id))) {
        return false;
      }
      boolean ok =
          transition(
              id,
              QaStatus.REFUNDED,
              r -> withNext(r.withStatus(QaStatus.REFUNDED).withRefundedAt(at), null));
      if (ok) {
        clearPrompt(id, 0);
      }
      return ok;
    }

    @Override
    public synchronized int purgeFree(Instant before) {
      List<UUID> ids =
          readings.values().stream()
              .filter(r -> r.kind() == QaKind.FREE && r.createdAt().isBefore(before) && !r.owed())
              .map(QaReading::id)
              .toList();
      ids.forEach(this::remove);
      return ids.size();
    }

    @Override
    public int purgeCache(Instant before) {
      purgedCacheBefore = before;
      return 0;
    }
  }
}
