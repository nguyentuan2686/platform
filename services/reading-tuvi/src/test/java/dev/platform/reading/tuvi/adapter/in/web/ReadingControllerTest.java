package dev.platform.reading.tuvi.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.platform.reading.tuvi.application.BasicReading;
import dev.platform.reading.tuvi.application.BasicReadingUseCase;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.TodayReading;
import dev.platform.reading.tuvi.application.TodayUseCase;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.Level;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ReadingController.class)
@Import({PlatformWebAutoConfiguration.class, ReadingExceptionHandler.class})
class ReadingControllerTest {

  static final String BIRTH_JSON =
      "{\"birth\":\"1994-03-12T08:30\",\"zone\":\"Asia/Ho_Chi_Minh\",\"gender\":\"male\","
          + "\"calendar\":\"VN\"";
  static final String BASIC = BIRTH_JSON + "}";
  static final String TODAY = BIRTH_JSON + ",\"date\":\"2026-11-24\"}";

  @Autowired MockMvc mvc;
  @MockitoBean BasicReadingUseCase basic;
  @MockitoBean TodayUseCase today;

  ListAppender<ILoggingEvent> logs;
  Logger root;
  ch.qos.logback.classic.Level previousLevel;

  @BeforeEach
  void captureLogs() {
    root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs = new ListAppender<>();
    logs.start();
    root.addAppender(logs);
    previousLevel = root.getLevel();
    root.setLevel(ch.qos.logback.classic.Level.ALL);
  }

  @AfterEach
  void releaseLogs() {
    root.detachAppender(logs);
    root.setLevel(previousLevel);
  }

  private void assertNoBirthDataLogged() {
    for (ILoggingEvent e : logs.list) {
      String line = e.getFormattedMessage() + " " + e.getThrowableProxy();
      assertThat(line).doesNotContain("1994-03-12", "08:30", "Asia/Ho_Chi_Minh");
    }
  }

  private ResultActions postJson(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void basic_returns_the_reading_and_omits_null_titles() throws Exception {
    when(basic.basic(any(Birth.class)))
        .thenReturn(
            new BasicReading(
                "LaoThienCo",
                "iztro-default",
                "abc",
                3,
                List.of(
                    new BasicReading.RenderedSection("greeting", null, List.of("Chào con")),
                    new BasicReading.RenderedSection("menh", "Cung Mệnh", List.of("a", "b")))));

    postJson("/tuvi/readings/basic", BASIC)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.character").value("LaoThienCo"))
        .andExpect(jsonPath("$.chartHash").value("abc"))
        .andExpect(jsonPath("$.knowledgeVersion").value(3))
        .andExpect(jsonPath("$.sections[0].id").value("greeting"))
        .andExpect(jsonPath("$.sections[0].title").doesNotExist())
        .andExpect(jsonPath("$.sections[1].title").value("Cung Mệnh"))
        .andExpect(jsonPath("$.sections[1].lines[1]").value("b"));
    assertNoBirthDataLogged();
  }

  @Test
  void today_returns_the_four_areas() throws Exception {
    List<TodayReading.AreaLine> areas =
        List.of(
            new TodayReading.AreaLine(Area.CongViec, "Công việc", Level.Thuan, "l1"),
            new TodayReading.AreaLine(Area.DiLai, "Đi lại", Level.Binh, "l2"),
            new TodayReading.AreaLine(Area.TienBac, "Tiền bạc", Level.LuuY, "l3"),
            new TodayReading.AreaLine(Area.SucKhoe, "Sức khỏe", Level.Binh, "l4"));
    when(today.today(any(Birth.class), any(LocalDate.class)))
        .thenReturn(
            new TodayReading(
                LocalDate.of(2026, 11, 24),
                "15/10",
                "Giáp Tý",
                "LaoThienCo",
                "open",
                areas,
                "close"));

    postJson("/tuvi/readings/today", TODAY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value("2026-11-24"))
        .andExpect(jsonPath("$.lunar").value("15/10"))
        .andExpect(jsonPath("$.dayCanChi").value("Giáp Tý"))
        .andExpect(jsonPath("$.opening").value("open"))
        .andExpect(jsonPath("$.areas.length()").value(4))
        .andExpect(jsonPath("$.areas[0].area").value("CongViec"))
        .andExpect(jsonPath("$.areas[0].level").value("Thuan"))
        .andExpect(jsonPath("$.areas[2].label").value("Tiền bạc"))
        .andExpect(jsonPath("$.closing").value("close"));
    assertNoBirthDataLogged();
  }

  @Test
  void malformed_json_is_400_on_body() throws Exception {
    postJson("/tuvi/readings/basic", "{\"birth\":\"1994-03-12T08:30\",")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("body"))
        .andExpect(jsonPath("$.trace_id").exists());
    assertNoBirthDataLogged();
  }

  @Test
  void empty_body_is_400_on_body() throws Exception {
    mvc.perform(post("/tuvi/readings/basic").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
  }

  @Test
  void unparseable_date_is_400_on_body_without_echoing_it() throws Exception {
    postJson("/tuvi/readings/today", BIRTH_JSON + ",\"date\":\"not-a-date\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not-a-date"))));
    assertNoBirthDataLogged();
  }

  @Test
  void today_without_date_is_400_on_date() throws Exception {
    postJson("/tuvi/readings/today", BASIC)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("date"))
        .andExpect(jsonPath("$.trace_id").exists());
    assertNoBirthDataLogged();
  }

  @Test
  void chart_svc_rejection_copies_its_errors() throws Exception {
    when(basic.basic(any(Birth.class)))
        .thenThrow(
            new UpstreamValidationException(
                List.of(
                    new UpstreamValidationException.FieldError(
                        "gender", "must be male or female"))));

    postJson("/tuvi/readings/basic", BASIC)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("gender"))
        .andExpect(jsonPath("$.errors[0].message").value("must be male or female"));
    assertNoBirthDataLogged();
  }

  @Test
  void upstream_unavailable_is_503_with_its_own_slug() throws Exception {
    when(today.today(any(Birth.class), any(LocalDate.class)))
        .thenThrow(new UpstreamUnavailableException("chart-svc unreachable"));

    postJson("/tuvi/readings/today", TODAY)
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:upstream-unavailable"))
        .andExpect(jsonPath("$.detail").value("Chart service unavailable"))
        .andExpect(jsonPath("$.trace_id").exists());
    assertNoBirthDataLogged();
  }

  @Test
  void an_unexpected_failure_is_a_generic_500_that_logs_no_birth_data() throws Exception {
    when(basic.basic(any(Birth.class))).thenThrow(new IllegalStateException("boom"));

    postJson("/tuvi/readings/basic", BASIC)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Unexpected error"));
    assertNoBirthDataLogged();
  }
}
