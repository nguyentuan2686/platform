package dev.platform.reading.tuvi.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.reading.tuvi.application.BasicReading;
import dev.platform.reading.tuvi.application.BasicReadingUseCase;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.TodayUseCase;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.qa.AccountRequiredException;
import dev.platform.reading.tuvi.application.qa.AskCommand;
import dev.platform.reading.tuvi.application.qa.FreeQuota;
import dev.platform.reading.tuvi.application.qa.Prepared;
import dev.platform.reading.tuvi.application.qa.QaConflictException;
import dev.platform.reading.tuvi.application.qa.QaInvalidRequestException;
import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.application.qa.QaNotFoundException;
import dev.platform.reading.tuvi.application.qa.QaOutcome;
import dev.platform.reading.tuvi.application.qa.QaService;
import dev.platform.reading.tuvi.application.qa.UnauthenticatedException;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({QaController.class, ReadingController.class})
@Import({
  PlatformWebAutoConfiguration.class,
  ReadingExceptionHandler.class,
  QaControllerTest.SyncExecutor.class
})
class QaControllerTest {

  static final String TOKEN = "local-test-internal-token";
  static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  static final String ASK =
      "{\"reading_id\":\"%s\",\"kind\":\"%s\",\"topic\":\"TinhDuyen\","
          + "\"question\":\"Tình duyên năm nay ra sao\",\"lang\":\"%s\"}";

  @TestConfiguration
  static class SyncExecutor {
    @Bean("qaExecutor")
    Executor qaExecutor() {
      return Runnable::run;
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean QaService qa;
  @MockitoBean BasicReadingUseCase basic;
  @MockitoBean TodayUseCase today;

  static MockHttpServletRequestBuilder ask(String body) {
    return post("/tuvi/qa/readings")
        .header("X-Internal-Token", TOKEN)
        .header("Authorization", "Bearer sess-1")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  static String body(String id, String kind, String lang) {
    return ASK.formatted(id, kind, lang);
  }

  @Test
  void bad_lang_is_400_on_lang() throws Exception {
    when(qa.prepare(any())).thenThrow(new QaInvalidRequestException("lang"));

    mvc.perform(ask(body(ID.toString(), "PAID", "en")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lang"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void bad_question_is_400_on_question() throws Exception {
    when(qa.prepare(any())).thenThrow(new QaInvalidRequestException("question"));

    mvc.perform(ask(body(ID.toString(), "PAID", "vi")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("question"));
  }

  @Test
  void malformed_reading_id_kind_or_topic_is_400() throws Exception {
    mvc.perform(ask(body("not-a-uuid", "PAID", "vi")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("reading_id"));
    mvc.perform(ask(body(ID.toString(), "GOLD", "vi")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("kind"));
    mvc.perform(ask(body(ID.toString(), "PAID", "vi").replace("TinhDuyen", "Love")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("topic"));
    mvc.perform(ask("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
    verifyNoInteractions(qa);
  }

  @Test
  void no_bearer_is_401() throws Exception {
    mvc.perform(
            post("/tuvi/qa/readings")
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(ID.toString(), "PAID", "vi")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
    mvc.perform(get("/tuvi/qa/readings").header("X-Internal-Token", TOKEN))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(qa);
  }

  @Test
  void invalid_session_is_401() throws Exception {
    when(qa.list("sess-1")).thenThrow(new UnauthenticatedException());

    mvc.perform(
            get("/tuvi/qa/readings")
                .header("X-Internal-Token", TOKEN)
                .header("Authorization", "Bearer sess-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
  }

  @Test
  void no_internal_token_on_qa_is_401() throws Exception {
    mvc.perform(
            get("/tuvi/qa/free-quota")
                .header("Authorization", "Bearer sess-1")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
    verifyNoInteractions(qa);
  }

  @Test
  void readings_basic_stays_open_without_internal_token() throws Exception {
    when(basic.basic(any(Birth.class)))
        .thenReturn(new BasicReading("LaoThienCo", "s", "a".repeat(64), 1, List.of()));

    mvc.perform(
            post("/tuvi/readings/basic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"birth\":\"1994-03-12T08:30\",\"gender\":\"male\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void guest_paid_is_403_account_required() throws Exception {
    when(qa.prepare(any())).thenThrow(new AccountRequiredException());

    mvc.perform(ask(body(ID.toString(), "PAID", "vi")))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:forbidden"))
        .andExpect(jsonPath("$.reason").value("account_required"));
  }

  @Test
  void free_quota_is_409() throws Exception {
    when(qa.prepare(any())).thenThrow(new QaConflictException("free_quota"));

    mvc.perform(ask(body(ID.toString(), "FREE", "vi")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:problem:conflict"))
        .andExpect(jsonPath("$.reason").value("free_quota"));
  }

  @Test
  void identity_down_is_503_identity() throws Exception {
    when(qa.prepare(any())).thenThrow(new UpstreamUnavailableException("identity"));

    mvc.perform(ask(body(ID.toString(), "PAID", "vi")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("urn:problem:upstream-unavailable"))
        .andExpect(jsonPath("$.detail").value("Identity service unavailable"));
  }

  @Test
  void chart_down_is_503_chart() throws Exception {
    when(qa.prepare(any())).thenThrow(new UpstreamUnavailableException("chart"));

    mvc.perform(ask(body(ID.toString(), "PAID", "vi")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("Chart service unavailable"));
  }

  @Test
  void other_subjects_reading_is_404() throws Exception {
    when(qa.get("sess-1", ID)).thenThrow(new QaNotFoundException());

    mvc.perform(
            get("/tuvi/qa/readings/" + ID)
                .header("X-Internal-Token", TOKEN)
                .header("Authorization", "Bearer sess-1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("urn:problem:not-found"));
    mvc.perform(
            get("/tuvi/qa/readings/nope")
                .header("X-Internal-Token", TOKEN)
                .header("Authorization", "Bearer sess-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ask_streams_meta_then_the_terminal_event() throws Exception {
    when(qa.prepare(any(AskCommand.class)))
        .thenReturn(
            new Prepared.Immediate(
                ID, QaKind.FREE, QaTopic.TinhDuyen, 0, new QaOutcome.Busy("Ta mỏi rồi.")));

    var result = mvc.perform(ask(body(ID.toString(), "FREE", "vi"))).andReturn();
    String sse = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    assertThat(sse).contains("event:meta").contains("event:busy").contains("Ta mỏi rồi.");
    assertThat(sse.indexOf("event:meta")).isLessThan(sse.indexOf("event:busy"));
  }

  @Test
  void free_quota_and_delete() throws Exception {
    when(qa.freeQuota("sess-1")).thenReturn(new FreeQuota(3, 1, 2));

    mvc.perform(
            get("/tuvi/qa/free-quota")
                .header("X-Internal-Token", TOKEN)
                .header("Authorization", "Bearer sess-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(3))
        .andExpect(jsonPath("$.used").value(1))
        .andExpect(jsonPath("$.remaining").value(2));
    mvc.perform(
            delete("/tuvi/qa/readings")
                .header("X-Internal-Token", TOKEN)
                .header("Authorization", "Bearer sess-1"))
        .andExpect(status().isNoContent());
  }
}
