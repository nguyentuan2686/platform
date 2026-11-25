package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.ReadingDetail;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /tuvi/qa/readings/{id}}. Nullable fields are written as {@code null}. {@code
 * toString} is redacted.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QaReadingResponse(
    @JsonProperty("reading_id") UUID readingId,
    String kind,
    String topic,
    String status,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("retry_until") Instant retryUntil,
    @JsonProperty("follow_ups_left") int followUpsLeft,
    String notice,
    String disclaimer,
    List<Message> messages) {

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Message(
      int seq, String question, String answer, @JsonProperty("created_at") Instant createdAt) {

    @Override
    public String toString() {
      return "Message[seq=" + seq + "]";
    }
  }

  static QaReadingResponse from(ReadingDetail d) {
    return new QaReadingResponse(
        d.readingId(),
        d.kind().name(),
        d.topic().name(),
        d.status().name(),
        d.createdAt(),
        d.retryUntil(),
        d.followUpsLeft(),
        d.notice(),
        d.disclaimer(),
        d.messages().stream()
            .map(m -> new Message(m.seq(), m.question(), m.answer(), m.createdAt()))
            .toList());
  }

  @Override
  public String toString() {
    return "QaReadingResponse[readingId=" + readingId + "]";
  }
}
