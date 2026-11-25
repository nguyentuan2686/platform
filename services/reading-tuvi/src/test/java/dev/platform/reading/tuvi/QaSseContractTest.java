package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.platform.reading.tuvi.adapter.in.web.QaController;
import dev.platform.reading.tuvi.adapter.in.web.ReadingExceptionHandler;
import dev.platform.reading.tuvi.application.qa.Prepared;
import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.application.qa.QaOutcome;
import dev.platform.reading.tuvi.application.qa.QaService;
import dev.platform.reading.tuvi.domain.qa.Prompt;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.reading.tuvi.domain.qa.RefusalReason;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every SSE {@code data} payload validates against its {@code QaEvent*} schema. */
@WebMvcTest(QaController.class)
@Import({
  PlatformWebAutoConfiguration.class,
  ReadingExceptionHandler.class,
  QaSseContractTest.SyncExecutor.class
})
class QaSseContractTest {

  static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  static final Map<String, String> SCHEMAS =
      Map.of(
          "meta", "QaEventMeta",
          "delta", "QaEventDelta",
          "done", "QaEventDone",
          "refused", "QaEventRefused",
          "queued", "QaEventQueued",
          "busy", "QaEventBusy",
          "error", "QaEventError");

  @TestConfiguration
  static class SyncExecutor {
    @Bean("qaExecutor")
    Executor qaExecutor() {
      return Runnable::run;
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean QaService qa;

  List<String> stream(Prepared prepared) throws Exception {
    when(qa.prepare(any())).thenReturn(prepared);
    String body =
        "{\"reading_id\":\""
            + ID
            + "\",\"kind\":\"PAID\",\"topic\":\"TinhDuyen\",\"question\":\"Tình duyên ra sao\","
            + "\"lang\":\"vi\"}";
    String sse =
        mvc.perform(
                post("/tuvi/qa/readings")
                    .header("X-Internal-Token", "local-test-internal-token")
                    .header("Authorization", "Bearer s")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    for (String block : sse.split("\n\n")) {
      String name = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring(6);
        } else if (line.startsWith("data:")) {
          data = line.substring(5);
        }
      }
      if (name == null) {
        continue;
      }
      JsonNode json = JsonMapper.shared().readTree(data);
      assertThat(ContractSupport.componentSchema(SCHEMAS.get(name)).validate(json))
          .as(name + " " + data)
          .isEmpty();
      names.add(name);
    }
    return names;
  }

  static Prepared immediate(QaOutcome outcome) {
    return new Prepared.Immediate(ID, QaKind.PAID, QaTopic.TinhDuyen, 2, outcome);
  }

  @Test
  void answered_stream() throws Exception {
    List<String> names =
        stream(
            immediate(
                new QaOutcome.Answered(
                    ID, 0, "Câu một. Câu hai! Câu ba? Hết…  Thêm", "Lời nhắc.", 2)));

    assertThat(names).containsExactly("meta", "delta", "delta", "delta", "delta", "delta", "done");
  }

  @Test
  void refused_queued_busy_streams() throws Exception {
    assertThat(
            stream(
                immediate(new QaOutcome.Refused(ID, RefusalReason.SELF_HARM, "Ta thương.", "L."))))
        .containsExactly("meta", "refused");
    assertThat(stream(immediate(new QaOutcome.Queued(ID, Instant.now(), "Đang ngẫm."))))
        .containsExactly("meta", "queued");
    assertThat(stream(immediate(new QaOutcome.Queued(ID, null, "Đang ngẫm."))))
        .containsExactly("meta", "queued");
    assertThat(stream(immediate(new QaOutcome.Busy("Mỏi rồi.")))).containsExactly("meta", "busy");
  }

  @Test
  void unexpected_error_after_streaming_started() throws Exception {
    Prepared.Job job =
        new Prepared.Job(
            ID,
            QaKind.PAID,
            QaTopic.TinhDuyen,
            2,
            0,
            Instant.now(),
            new Prompt(List.of(new Prompt.Msg("user", "q")), 100),
            List.of(),
            "key");
    when(qa.generate(job)).thenThrow(new IllegalStateException("boom"));

    assertThat(stream(job)).containsExactly("meta", "error");
  }

  @Test
  void schemas_are_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"x\":1}");
    SCHEMAS
        .values()
        .forEach(s -> assertThat(ContractSupport.componentSchema(s).validate(bad)).isNotEmpty());
  }
}
