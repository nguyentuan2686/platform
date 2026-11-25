package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.ChartPort;
import dev.platform.reading.tuvi.application.ChartResult;
import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.application.llm.AllProvidersExhaustedException;
import dev.platform.reading.tuvi.application.llm.LlmCompletion;
import dev.platform.reading.tuvi.application.llm.LlmGateway;
import dev.platform.reading.tuvi.application.llm.LlmMessage;
import dev.platform.reading.tuvi.application.llm.LlmRequest;
import dev.platform.reading.tuvi.application.llm.LlmTier;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Answered;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Busy;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Queued;
import dev.platform.reading.tuvi.application.qa.QaOutcome.Refused;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.NewMessage;
import dev.platform.reading.tuvi.application.qa.QaRepositoryPort.StoredMessage;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.qa.AnswerFilter;
import dev.platform.reading.tuvi.domain.qa.AnswerLimits;
import dev.platform.reading.tuvi.domain.qa.FilterResult;
import dev.platform.reading.tuvi.domain.qa.GuardResult;
import dev.platform.reading.tuvi.domain.qa.PiiScrubber;
import dev.platform.reading.tuvi.domain.qa.Prompt;
import dev.platform.reading.tuvi.domain.qa.PromptBuilder;
import dev.platform.reading.tuvi.domain.qa.PromptKind;
import dev.platform.reading.tuvi.domain.qa.QaContext;
import dev.platform.reading.tuvi.domain.qa.QaContextBuilder;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.QuestionGuard;
import dev.platform.reading.tuvi.domain.qa.QuestionNormalizer;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import dev.platform.reading.tuvi.domain.qa.Sentinel;
import dev.platform.reading.tuvi.domain.qa.Turn;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paid questions, free chats and follow-ups (spec §14, §15). {@link #prepare} runs on the request
 * thread: validation, identity, guard, chart and (for PAID) the charge, so every failure there is a
 * plain HTTP problem and nothing is charged unless the question will reach the model. {@link
 * #generate} runs on the Q&A executor and never charges. Logs carry ids, kinds, topics, statuses
 * and provider ids only.
 */
public class QaService {

  private static final Logger LOG = LoggerFactory.getLogger(QaService.class);

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final String LANG = "vi";
  static final int MAX_FOLLOW_UPS = 2;
  static final int MAX_SNIPPETS = 12;
  static final int MAX_SNIPPET_CHARS = 3500;
  static final double TEMPERATURE = 0.6;
  static final Duration FIRST_RETRY = Duration.ofMinutes(5);
  static final int HISTORY_LIMIT = 50;

  private final IdentityPort identity;
  private final ChartPort charts;
  private final KnowledgePort knowledge;
  private final LlmGateway gateway;
  private final QaRepositoryPort repo;
  private final QaStore store;
  private final QaAnswerCachePort cache;
  private final QaTextCipherPort cipher;
  private final QaFrames frames;
  private final Clock clock;
  private final QaSettings settings;

  public QaService(
      IdentityPort identity,
      ChartPort charts,
      KnowledgePort knowledge,
      LlmGateway gateway,
      QaRepositoryPort repo,
      QaStore store,
      QaAnswerCachePort cache,
      QaTextCipherPort cipher,
      QaFrames frames,
      Clock clock,
      QaSettings settings) {
    this.identity = identity;
    this.charts = charts;
    this.knowledge = knowledge;
    this.gateway = gateway;
    this.repo = repo;
    this.store = store;
    this.cache = cache;
    this.cipher = cipher;
    this.frames = frames;
    this.clock = clock;
    this.settings = settings;
  }

  // ---------------------------------------------------------------- new questions

  /**
   * Validation, identity, replay, guard, profile, chart, insert and, for PAID, the charge. A
   * returned {@link Prepared.Job} is stored (and charged when PAID) and must be passed to {@link
   * #generate} or {@link #rejected}.
   */
  public Prepared prepare(AskCommand cmd) {
    if (!LANG.equals(cmd.lang())) {
      throw new QaInvalidRequestException("lang");
    }
    GuardResult guard = QuestionGuard.check(cmd.question());
    if (guard instanceof GuardResult.Invalid invalid) {
      throw new QaInvalidRequestException(invalid.field());
    }
    IdentitySubject subject = identity.whoAmI(cmd.bearer());
    if (cmd.kind() == QaKind.PAID && subject.guest()) {
      throw new AccountRequiredException();
    }
    Optional<QaReading> existing = repo.find(cmd.readingId());
    if (existing.isPresent()) {
      return replay(existing.get(), subject);
    }
    String question = QuestionNormalizer.clean(cmd.question());
    if (guard instanceof GuardResult.Refused refused) {
      return refuse(cmd, subject, question, refused.reason());
    }
    String sanitized = ((GuardResult.Ok) guard).sanitized();
    Birth birth =
        identity.profile(cmd.bearer()).orElseThrow(() -> new QaConflictException("no_profile"));
    ChartResult chart = charts.chart(birth);
    Optional<FlowLayer> yearly = yearly(cmd.topic(), birth);

    Instant now = clock.instant();
    UUID id = cmd.readingId();
    PromptKind promptKind = cmd.kind() == QaKind.PAID ? PromptKind.PAID : PromptKind.FREE;
    Built built = build(chart, yearly, cmd.topic(), sanitized, List.of(), promptKind);
    String cacheKey = cacheKey(chart.chartHash(), cmd.topic(), cmd.kind(), sanitized);
    QaReading reading =
        QaReading.create(
            id,
            subject,
            cmd.kind(),
            cmd.topic(),
            LANG,
            chart.chartHash(),
            QaStatus.PENDING,
            null,
            now);
    NewMessage first =
        new NewMessage(
            0,
            id,
            cipher.encrypt(aad(id, 0), question),
            cipher.encrypt(promptAad(id, 0), PromptCodec.encode(built.prompt())));
    int followUps = cmd.kind() == QaKind.PAID ? MAX_FOLLOW_UPS : 0;

    if (cmd.kind() == QaKind.FREE) {
      QaStore.FreeInsert inserted = store.insertFree(reading, first, settings, vnMidnight(now));
      if (inserted == QaStore.FreeInsert.BUSY) {
        LOG.info("qa free global cap reached reading_id={}", id);
        return new Prepared.Immediate(id, cmd.kind(), cmd.topic(), 0, new Busy(frames.busy()));
      }
      if (inserted == QaStore.FreeInsert.DUPLICATE) {
        throw new QaConflictException("reading_in_progress");
      }
    } else {
      if (!store.insert(reading, first)) {
        throw new QaConflictException("reading_in_progress");
      }
      charge(subject, id, cmd.topic());
    }
    LOG.info(
        "qa reading accepted reading_id={} kind={} topic={} status=PENDING",
        id,
        cmd.kind(),
        cmd.topic());
    return new Prepared.Job(
        id, cmd.kind(), cmd.topic(), followUps, 0, now, built.prompt(), built.usedKeys(), cacheKey);
  }

  private void charge(IdentitySubject subject, UUID id, QaTopic topic) {
    ConsumeOutcome outcome;
    try {
      outcome = identity.consume(subject.id(), id, topic);
    } catch (RuntimeException e) {
      // a timeout may still have charged: compensate with a refund (retry job)
      repo.markRefundPending(id, "consume_unavailable");
      LOG.warn("qa consume failed, refund pending reading_id={}", id);
      throw e;
    }
    switch (outcome) {
      case ConsumeOutcome.Charged charged -> repo.markCharged(id, charged.source());
      case ConsumeOutcome.Refused refused -> {
        repo.delete(id);
        LOG.info("qa consume refused reading_id={} reason={}", id, refused.reason());
        throw new QaConflictException(refused.reason());
      }
    }
  }

  private Prepared replay(QaReading r, IdentitySubject subject) {
    if (!r.subjectId().equals(subject.id())) {
      throw new QaConflictException("reading_conflict");
    }
    int left = followUpsLeft(r, repo.countFollowUps(r.id()));
    QaOutcome outcome =
        switch (r.status()) {
          case ANSWERED -> {
            StoredMessage first = repo.messages(r.id()).getFirst();
            yield new Answered(
                r.id(), 0, cipher.decrypt(aad(r.id(), 0), first.answerEnc()), disclaimer(), left);
          }
          case REFUSED -> refusedOutcome(r.id(), RefusalReason.valueOf(r.refusalReason()));
          case RETRYING, REFUND_PENDING -> new Queued(r.id(), r.retryUntil(), frames.queued());
          case REFUNDED, FAILED -> throw new QaConflictException("reading_refunded");
          case PENDING -> throw new QaConflictException("reading_in_progress");
        };
    return new Prepared.Immediate(r.id(), r.kind(), r.topic(), left, outcome);
  }

  private Prepared refuse(
      AskCommand cmd, IdentitySubject subject, String question, RefusalReason reason) {
    UUID id = cmd.readingId();
    QaReading reading =
        QaReading.create(
            id,
            subject,
            cmd.kind(),
            cmd.topic(),
            LANG,
            "",
            QaStatus.REFUSED,
            reason.name(),
            clock.instant());
    NewMessage first = new NewMessage(0, id, cipher.encrypt(aad(id, 0), question), null);
    if (!store.insert(reading, first)) {
      throw new QaConflictException("reading_in_progress");
    }
    LOG.info(
        "qa question refused reading_id={} kind={} topic={} reason={}",
        id,
        cmd.kind(),
        cmd.topic(),
        reason);
    return new Prepared.Immediate(id, cmd.kind(), cmd.topic(), 0, refusedOutcome(id, reason));
  }

  // ---------------------------------------------------------------- follow-ups

  /** A follow-up on the caller's own answered PAID reading; never calls identity's credits. */
  public Prepared prepareFollowUp(FollowUpCommand cmd) {
    GuardResult guard = QuestionGuard.check(cmd.question());
    if (guard instanceof GuardResult.Invalid invalid) {
      throw new QaInvalidRequestException(invalid.field());
    }
    IdentitySubject subject = identity.whoAmI(cmd.bearer());
    QaReading reading =
        repo.find(cmd.readingId())
            .filter(r -> r.subjectId().equals(subject.id()))
            .orElseThrow(QaNotFoundException::new);
    Optional<StoredMessage> prior = repo.messageByClientId(cmd.messageId());
    if (prior.isPresent()) {
      return replayMessage(reading, prior.get());
    }
    if (reading.kind() != QaKind.PAID || reading.status() != QaStatus.ANSWERED) {
      throw new QaConflictException("reading_not_answered");
    }
    int used = repo.countFollowUps(reading.id());
    if (used >= MAX_FOLLOW_UPS) {
      throw new QaConflictException("follow_ups_exhausted");
    }
    if (guard instanceof GuardResult.Refused refused) {
      LOG.info("qa follow-up refused reading_id={} reason={}", reading.id(), refused.reason());
      return new Prepared.Immediate(
          reading.id(),
          reading.kind(),
          reading.topic(),
          MAX_FOLLOW_UPS - used,
          refusedOutcome(reading.id(), refused.reason()));
    }
    String sanitized = ((GuardResult.Ok) guard).sanitized();
    String question = QuestionNormalizer.clean(cmd.question());
    Birth birth =
        identity.profile(cmd.bearer()).orElseThrow(() -> new QaConflictException("no_profile"));
    ChartResult chart = charts.chart(birth);
    Optional<FlowLayer> yearly = yearly(reading.topic(), birth);

    Instant now = clock.instant();
    UUID id = reading.id();
    QaStore.FollowUpSlot slot =
        store.reserveFollowUp(
            id,
            subject.id(),
            cmd.messageId(),
            seq ->
                new NewMessage(seq, cmd.messageId(), cipher.encrypt(aad(id, seq), question), null),
            now);
    return switch (slot) {
      case QaStore.FollowUpSlot.Replay replay -> replayMessage(reading, replay.message());
      case QaStore.FollowUpSlot.Reserved reserved -> {
        List<Turn> history = new ArrayList<>();
        for (StoredMessage m : reserved.earlier()) {
          if (m.answerEnc() != null) {
            history.add(
                new Turn(
                    sanitize(cipher.decrypt(aad(id, m.seq()), m.questionEnc())),
                    cipher.decrypt(aad(id, m.seq()), m.answerEnc())));
          }
        }
        Built built =
            build(chart, yearly, reading.topic(), sanitized, history, PromptKind.FOLLOW_UP);
        LOG.info("qa follow-up accepted reading_id={} seq={}", id, reserved.seq());
        yield new Prepared.Job(
            id,
            reading.kind(),
            reading.topic(),
            MAX_FOLLOW_UPS - reserved.seq(),
            reserved.seq(),
            now,
            built.prompt(),
            built.usedKeys(),
            null);
      }
    };
  }

  private Prepared replayMessage(QaReading reading, StoredMessage m) {
    if (!m.readingId().equals(reading.id())) {
      throw new QaConflictException("reading_conflict");
    }
    if (m.answerEnc() == null) {
      throw new QaConflictException("reading_in_progress");
    }
    int left = Math.max(0, MAX_FOLLOW_UPS - m.seq());
    return new Prepared.Immediate(
        reading.id(),
        reading.kind(),
        reading.topic(),
        left,
        new Answered(
            reading.id(),
            m.seq(),
            cipher.decrypt(aad(reading.id(), m.seq()), m.answerEnc()),
            disclaimer(),
            left));
  }

  // ---------------------------------------------------------------- generation

  /**
   * Cache, model (one regeneration on a filter reject), sentinels and the answer filter. On an
   * unexpected exception a charged reading goes {@code RETRYING} and the exception is rethrown.
   */
  public QaOutcome generate(Prepared.Job job) {
    try {
      return doGenerate(job);
    } catch (RuntimeException e) {
      LOG.warn(
          "qa generation failed reading_id={} error={}",
          job.readingId(),
          e.getClass().getSimpleName());
      try {
        failure(job, "internal");
      } catch (RuntimeException secondary) {
        LOG.warn(
            "qa failure bookkeeping failed reading_id={} error={}",
            job.readingId(),
            secondary.getClass().getSimpleName());
      }
      throw e;
    }
  }

  /** The executor refused the job (queue full): handled like a model failure. */
  public QaOutcome rejected(Prepared.Job job) {
    LOG.warn("qa executor full reading_id={}", job.readingId());
    return failure(job, "executor_full");
  }

  private QaOutcome doGenerate(Prepared.Job job) {
    Optional<String> cached = cached(job);
    if (cached.isPresent()) {
      return answered(job, cached.get(), "cache", List.of(), false);
    }
    LlmRequest request = request(job);
    String rule = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      LlmCompletion completion;
      try {
        completion = gateway.complete(request);
      } catch (AllProvidersExhaustedException e) {
        return failure(job, "all_providers_exhausted");
      }
      Optional<RefusalReason> sentinel = Sentinel.detect(completion.text());
      if (sentinel.isPresent()) {
        return sentinelRefusal(job, sentinel.get());
      }
      FilterResult filtered = AnswerFilter.check(completion.text(), limits(job));
      if (filtered instanceof FilterResult.Pass pass) {
        return answered(job, pass.cleaned(), completion.provider(), job.usedKeys(), true);
      }
      rule = ((FilterResult.Reject) filtered).rule();
      LOG.info(
          "qa answer rejected reading_id={} provider={} rule={}",
          job.readingId(),
          completion.provider(),
          rule);
    }
    return failure(job, "filter:" + rule);
  }

  private Optional<String> cached(Prepared.Job job) {
    if (job.cacheKey() == null) {
      return Optional.empty();
    }
    try {
      return cache.find(job.cacheKey()).map(enc -> cipher.decrypt(job.cacheKey(), enc));
    } catch (RuntimeException e) {
      LOG.warn("qa cache lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private QaOutcome answered(
      Prepared.Job job, String text, String provider, List<String> keys, boolean putCache) {
    UUID id = job.readingId();
    store.answer(
        id, job.seq(), cipher.encrypt(aad(id, job.seq()), text), provider, keys, clock.instant());
    if (putCache && job.cacheKey() != null) {
      try {
        cache.put(job.cacheKey(), cipher.encrypt(job.cacheKey(), text));
      } catch (RuntimeException e) {
        LOG.warn("qa cache store failed: {}", e.getClass().getSimpleName());
      }
    }
    LOG.info(
        "qa answered reading_id={} kind={} topic={} seq={} provider={} status=ANSWERED",
        id,
        job.kind(),
        job.topic(),
        job.seq(),
        provider);
    return new Answered(id, job.seq(), text, disclaimer(), job.followUpsLeft());
  }

  private QaOutcome sentinelRefusal(Prepared.Job job, RefusalReason reason) {
    UUID id = job.readingId();
    LOG.info("qa model declined reading_id={} seq={} reason={}", id, job.seq(), reason);
    if (job.followUp()) {
      repo.deleteMessage(id, job.seq());
      return refusedOutcome(id, reason);
    }
    repo.markRefused(id, reason);
    if (job.kind() == QaKind.PAID) {
      refundRefused(id);
    }
    return refusedOutcome(id, reason);
  }

  private void refundRefused(UUID id) {
    try {
      if (identity.refund(id) == RefundOutcome.REFUNDED) {
        repo.recordRefund(id, clock.instant());
      }
    } catch (RuntimeException e) {
      repo.markRefundPending(id, "refund_unavailable");
      LOG.warn("qa refund pending reading_id={}", id);
    }
  }

  /** Model unavailable, two filter rejects, a full executor or an unexpected error. */
  private QaOutcome failure(Prepared.Job job, String code) {
    UUID id = job.readingId();
    if (job.followUp()) {
      repo.deleteMessage(id, job.seq());
      LOG.info("qa follow-up failed reading_id={} seq={} error={}", id, job.seq(), code);
      return new Busy(frames.busy());
    }
    if (job.kind() == QaKind.PAID) {
      Instant until = job.createdAt().plus(settings.retryWindow());
      repo.markRetrying(id, 1, clock.instant().plus(FIRST_RETRY), until, code);
      LOG.info("qa queued reading_id={} status=RETRYING error={}", id, code);
      return new Queued(id, until, frames.queued());
    }
    repo.markFailed(id, code);
    LOG.info("qa free chat failed reading_id={} status=FAILED error={}", id, code);
    return new Busy(frames.busy());
  }

  // ---------------------------------------------------------------- history

  public List<ReadingSummary> list(String bearer) {
    IdentitySubject subject = identity.whoAmI(bearer);
    return repo.listBySubject(subject.id(), HISTORY_LIMIT).stream()
        .map(
            l ->
                new ReadingSummary(
                    l.reading().id(),
                    l.reading().kind(),
                    l.reading().topic(),
                    l.reading().status(),
                    l.reading().createdAt(),
                    followUpsLeft(l.reading(), l.followUps())))
        .toList();
  }

  /** Another subject's reading is reported as not found. */
  public ReadingDetail get(String bearer, UUID readingId) {
    IdentitySubject subject = identity.whoAmI(bearer);
    QaReading r =
        repo.find(readingId)
            .filter(x -> x.subjectId().equals(subject.id()))
            .orElseThrow(QaNotFoundException::new);
    List<StoredMessage> stored = repo.messages(r.id());
    List<ReadingDetail.Message> messages = new ArrayList<>();
    for (StoredMessage m : stored) {
      String aad = aad(r.id(), m.seq());
      messages.add(
          new ReadingDetail.Message(
              m.seq(),
              cipher.decrypt(aad, m.questionEnc()),
              m.answerEnc() == null ? null : cipher.decrypt(aad, m.answerEnc()),
              m.createdAt()));
    }
    String notice =
        switch (r.status()) {
          case RETRYING, REFUND_PENDING -> frames.queued();
          case REFUNDED -> frames.refunded();
          default -> null;
        };
    int followUps = (int) stored.stream().filter(m -> m.seq() > 0).count();
    return new ReadingDetail(
        r.id(),
        r.kind(),
        r.topic(),
        r.status(),
        r.createdAt(),
        r.retryUntil(),
        followUpsLeft(r, followUps),
        notice,
        disclaimer(),
        List.copyOf(messages));
  }

  public void deleteAll(String bearer) {
    IdentitySubject subject = identity.whoAmI(bearer);
    store.deleteAll(subject.id());
    LOG.info("qa history deleted");
  }

  public FreeQuota freeQuota(String bearer) {
    IdentitySubject subject = identity.whoAmI(bearer);
    int limit = settings.freeDailyPerSubject();
    int used = repo.countFreeBySubject(subject.id(), vnMidnight(clock.instant()));
    return new FreeQuota(limit, used, Math.max(0, limit - used));
  }

  // ---------------------------------------------------------------- helpers

  private record Built(Prompt prompt, List<String> usedKeys) {}

  /** Context, snippets (first hit per chain, at most 12 and 3,500 chars) and the prompt. */
  private Built build(
      ChartResult chart,
      Optional<FlowLayer> yearly,
      QaTopic topic,
      String sanitized,
      List<Turn> history,
      PromptKind kind) {
    QaContext ctx =
        QaContextBuilder.build(
            chart.chart(), yearly, topic, QuestionNormalizer.normalize(sanitized));
    List<String> snippets = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    int chars = 0;
    chains:
    for (List<KnowledgeKey> chain : ctx.lookupChains()) {
      if (snippets.size() >= MAX_SNIPPETS) {
        break;
      }
      for (KnowledgeKey key : chain) {
        Optional<String> text = knowledge.find(key, LANG);
        if (text.isEmpty()) {
          continue;
        }
        if (!keys.contains(key.value())) {
          String snippet = text.get().replace("{", "").replace("}", "");
          if (chars + snippet.length() > MAX_SNIPPET_CHARS) {
            break chains;
          }
          chars += snippet.length();
          snippets.add(snippet);
          keys.add(key.value());
        }
        break;
      }
    }
    Prompt prompt = PromptBuilder.build(topic, LANG, snippets, ctx, sanitized, history, kind);
    return new Built(prompt, List.copyOf(keys));
  }

  private Optional<FlowLayer> yearly(QaTopic topic, Birth birth) {
    if (topic != QaTopic.VanNam) {
      return Optional.empty();
    }
    LocalDate today = clock.instant().atZone(VN).toLocalDate();
    return Optional.of(charts.daily(birth, today).facts().yearly());
  }

  private LlmRequest request(Prepared.Job job) {
    List<LlmMessage> messages =
        job.prompt().messages().stream()
            .map(
                m ->
                    new LlmMessage(
                        LlmMessage.Role.valueOf(m.role().toUpperCase(Locale.ROOT)), m.content()))
            .toList();
    LlmTier tier = job.kind() == QaKind.FREE ? LlmTier.FREE_ONLY : LlmTier.ANY;
    return new LlmRequest(messages, job.prompt().maxTokens(), TEMPERATURE, tier);
  }

  private static AnswerLimits limits(Prepared.Job job) {
    if (job.followUp()) {
      return AnswerLimits.FOLLOW_UP;
    }
    return job.kind() == QaKind.PAID ? AnswerLimits.PAID : AnswerLimits.FREE;
  }

  private Refused refusedOutcome(UUID id, RefusalReason reason) {
    return new Refused(id, reason, frames.forRefusal(reason), disclaimer());
  }

  private String disclaimer() {
    return frames.disclaimer();
  }

  private static int followUpsLeft(QaReading r, int followUps) {
    if (r.kind() != QaKind.PAID || r.status() != QaStatus.ANSWERED) {
      return 0;
    }
    return Math.max(0, MAX_FOLLOW_UPS - followUps);
  }

  /** The question as the model sees it (cleaned, personal data hidden). */
  private static String sanitize(String question) {
    return QuestionGuard.check(question) instanceof GuardResult.Ok ok
        ? ok.sanitized()
        : PiiScrubber.scrub(QuestionNormalizer.clean(question));
  }

  private String cacheKey(String chartHash, QaTopic topic, QaKind kind, String sanitized) {
    String material =
        String.join(
            "|",
            chartHash,
            topic.name(),
            kind.name(),
            LANG,
            Long.toString(knowledge.version()),
            Integer.toString(PromptBuilder.PROMPT_VERSION),
            QuestionNormalizer.normalize(sanitized));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Instant vnMidnight(Instant now) {
    return now.atZone(VN).toLocalDate().atStartOfDay(VN).toInstant();
  }

  static String aad(UUID readingId, int seq) {
    return readingId + ":" + seq;
  }

  static String promptAad(UUID readingId, int seq) {
    return readingId + ":" + seq + ":prompt";
  }
}
