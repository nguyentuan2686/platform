package dev.platform.reading.tuvi.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.reading.tuvi.application.qa.AskCommand;
import dev.platform.reading.tuvi.application.qa.QaInvalidRequestException;
import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.util.UUID;

/**
 * Body of {@code POST /tuvi/qa/readings}. Fields are parsed by hand so a bad value is reported on
 * its own field; {@code lang} and {@code question} are checked by the service. {@code toString} is
 * redacted.
 */
public record AskRequest(
    @JsonProperty("reading_id") String readingId,
    String kind,
    String topic,
    String question,
    String lang) {

  AskCommand toCommand(String bearer) {
    return new AskCommand(
        bearer,
        uuid(readingId, "reading_id"),
        parse(QaKind.class, kind, "kind"),
        parse(QaTopic.class, topic, "topic"),
        question,
        lang);
  }

  static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      throw new QaInvalidRequestException(field);
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    try {
      return Enum.valueOf(type, value);
    } catch (RuntimeException e) {
      throw new QaInvalidRequestException(field);
    }
  }

  @Override
  public String toString() {
    return "AskRequest[redacted]";
  }
}
