package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.Prepared;
import dev.platform.reading.tuvi.application.qa.QaOutcome;
import dev.platform.reading.tuvi.application.qa.QaService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams a prepared question: {@code meta}, then either the deltas and {@code done}, or one of
 * {@code refused}, {@code queued}, {@code busy}, {@code error}; then completes. The answer is
 * generated in full first (buffered), then sent one sentence per {@code delta}; the web paces it. A
 * client that disconnects does not stop generation: the answer is stored for the history.
 */
final class QaSseWriter {

  private static final Logger LOG = LoggerFactory.getLogger(QaSseWriter.class);
  private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]+\\s+");

  record Meta(
      @JsonProperty("reading_id") UUID readingId,
      String kind,
      String topic,
      @JsonProperty("follow_ups_left") int followUpsLeft) {}

  record Delta(String text) {}

  record Done(@JsonProperty("reading_id") UUID readingId, int seq, String disclaimer) {}

  record Refused(
      @JsonProperty("reading_id") UUID readingId, String reason, String text, String disclaimer) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record Queued(
      @JsonProperty("reading_id") UUID readingId,
      @JsonProperty("retry_until") Instant retryUntil,
      String text) {}

  record Busy(String text) {}

  record Failure(String reason) {}

  private final QaService qa;
  private final Executor executor;
  private final Duration timeout;

  QaSseWriter(QaService qa, Executor executor, Duration timeout) {
    this.qa = qa;
    this.executor = executor;
    this.timeout = timeout;
  }

  SseEmitter stream(Prepared prepared) {
    SseEmitter emitter = new SseEmitter(timeout.toMillis());
    send(
        emitter,
        "meta",
        new Meta(
            prepared.readingId(),
            prepared.kind().name(),
            prepared.topic().name(),
            prepared.followUpsLeft()));
    switch (prepared) {
      case Prepared.Immediate immediate -> finish(emitter, immediate.outcome());
      case Prepared.Job job -> {
        try {
          executor.execute(() -> run(emitter, job));
        } catch (RejectedExecutionException e) {
          finish(emitter, qa.rejected(job));
        }
      }
    }
    return emitter;
  }

  private void run(SseEmitter emitter, Prepared.Job job) {
    QaOutcome outcome;
    try {
      outcome = qa.generate(job);
    } catch (RuntimeException e) {
      LOG.warn(
          "qa stream error reading_id={} error={}", job.readingId(), e.getClass().getSimpleName());
      send(emitter, "error", new Failure("internal"));
      complete(emitter);
      return;
    }
    finish(emitter, outcome);
  }

  private void finish(SseEmitter emitter, QaOutcome outcome) {
    switch (outcome) {
      case QaOutcome.Answered a -> {
        for (String sentence : sentences(a.text())) {
          send(emitter, "delta", new Delta(sentence));
        }
        send(emitter, "done", new Done(a.readingId(), a.seq(), a.disclaimer()));
      }
      case QaOutcome.Refused r ->
          send(
              emitter,
              "refused",
              new Refused(
                  r.readingId(),
                  r.reason().name().toLowerCase(Locale.ROOT),
                  r.text(),
                  r.disclaimer()));
      case QaOutcome.Queued q ->
          send(emitter, "queued", new Queued(q.readingId(), q.retryUntil(), q.text()));
      case QaOutcome.Busy b -> send(emitter, "busy", new Busy(b.text()));
    }
    complete(emitter);
  }

  /** Splits after each run of {@code .!?…} followed by whitespace; the parts concatenate back. */
  static List<String> sentences(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = SENTENCE_END.matcher(text);
    int start = 0;
    while (m.find()) {
      out.add(text.substring(start, m.end()));
      start = m.end();
    }
    if (start < text.length()) {
      out.add(text.substring(start));
    }
    return out;
  }

  private static void send(SseEmitter emitter, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException e) {
      // client gone or stream timed out: the outcome is already stored
      LOG.debug("qa stream send skipped: {}", e.getClass().getSimpleName());
    }
  }

  private static void complete(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (IllegalStateException e) {
      LOG.debug("qa stream already completed");
    }
  }
}
