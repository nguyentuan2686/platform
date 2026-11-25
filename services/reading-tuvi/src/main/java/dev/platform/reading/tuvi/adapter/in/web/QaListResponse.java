package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.ReadingSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Body of {@code GET /tuvi/qa/readings}: newest 50. */
public record QaListResponse(List<Item> readings) {

  public record Item(
      @JsonProperty("reading_id") UUID readingId,
      String kind,
      String topic,
      String status,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("follow_ups_left") int followUpsLeft) {}

  static QaListResponse from(List<ReadingSummary> list) {
    return new QaListResponse(
        list.stream()
            .map(
                s ->
                    new Item(
                        s.readingId(),
                        s.kind().name(),
                        s.topic().name(),
                        s.status().name(),
                        s.createdAt(),
                        s.followUpsLeft()))
            .toList());
  }
}
