package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.qa.QaNotFoundException;
import dev.platform.reading.tuvi.application.qa.QaService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Q&A for the web BFF: every path needs {@code X-Internal-Token} (platform filter) and the user's
 * session as {@code Authorization: Bearer}. Errors before the stream starts are problem+json.
 */
@RestController
public class QaController {

  private final QaService qa;
  private final QaSseWriter sse;

  public QaController(
      QaService qa,
      @Qualifier("qaExecutor") Executor executor,
      @Value("${qa.stream-timeout:PT90S}") Duration streamTimeout) {
    this.qa = qa;
    this.sse = new QaSseWriter(qa, executor, streamTimeout);
  }

  @PostMapping("/tuvi/qa/readings")
  SseEmitter ask(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody AskRequest request) {
    String bearer = Bearer.require(authorization);
    return sse.stream(qa.prepare(request.toCommand(bearer)));
  }

  @PostMapping("/tuvi/qa/readings/{id}/follow-ups")
  SseEmitter followUp(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable("id") String id,
      @RequestBody FollowUpRequest request) {
    String bearer = Bearer.require(authorization);
    return sse.stream(qa.prepareFollowUp(request.toCommand(bearer, readingId(id))));
  }

  @GetMapping("/tuvi/qa/readings")
  QaListResponse list(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return QaListResponse.from(qa.list(Bearer.require(authorization)));
  }

  @GetMapping("/tuvi/qa/readings/{id}")
  QaReadingResponse get(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable("id") String id) {
    String bearer = Bearer.require(authorization);
    return QaReadingResponse.from(qa.get(bearer, readingId(id)));
  }

  @DeleteMapping("/tuvi/qa/readings")
  ResponseEntity<Void> deleteAll(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    qa.deleteAll(Bearer.require(authorization));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/tuvi/qa/free-quota")
  FreeQuotaResponse freeQuota(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return FreeQuotaResponse.from(qa.freeQuota(Bearer.require(authorization)));
  }

  /** A malformed id cannot name a reading: 404, like an unknown one. */
  private static UUID readingId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new QaNotFoundException();
    }
  }
}
