package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.platform.reading.tuvi.application.BasicReading;
import java.util.List;

public record BasicReadingResponse(
    String character,
    String school,
    String chartHash,
    long knowledgeVersion,
    List<SectionResponse> sections) {

  /** {@code title} is omitted for sections without one. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SectionResponse(String id, String title, List<String> lines) {}

  static BasicReadingResponse from(BasicReading r) {
    return new BasicReadingResponse(
        r.character(),
        r.school(),
        r.chartHash(),
        r.knowledgeVersion(),
        r.sections().stream().map(s -> new SectionResponse(s.id(), s.title(), s.lines())).toList());
  }
}
