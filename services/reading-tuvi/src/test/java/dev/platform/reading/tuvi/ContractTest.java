package dev.platform.reading.tuvi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.platform.reading.tuvi.adapter.in.web.QaController;
import dev.platform.reading.tuvi.adapter.in.web.QaMergeController;
import dev.platform.reading.tuvi.adapter.in.web.ReadingController;
import dev.platform.reading.tuvi.application.BasicReading;
import dev.platform.reading.tuvi.application.BasicReadingUseCase;
import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.TodayReading;
import dev.platform.reading.tuvi.application.TodayUseCase;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.application.UpstreamValidationException;
import dev.platform.reading.tuvi.application.qa.FreeQuota;
import dev.platform.reading.tuvi.application.qa.QaConflictException;
import dev.platform.reading.tuvi.application.qa.QaKind;
import dev.platform.reading.tuvi.application.qa.QaMergeUseCase;
import dev.platform.reading.tuvi.application.qa.QaService;
import dev.platform.reading.tuvi.application.qa.QaStatus;
import dev.platform.reading.tuvi.application.qa.ReadingDetail;
import dev.platform.reading.tuvi.application.qa.ReadingSummary;
import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.Level;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({
  ReadingController.class,
  QaController.class,
  QaMergeController.class,
  dev.platform.reading.tuvi.adapter.in.web.NumberCheckController.class
})
@Import({
  PlatformWebAutoConfiguration.class,
  dev.platform.reading.tuvi.adapter.in.web.ReadingExceptionHandler.class,
  QaSseContractTest.SyncExecutor.class
})
class ContractTest {

  static final String INTERNAL = "local-test-internal-token";
  static final UUID QA_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @MockitoBean QaService qa;
  @MockitoBean QaMergeUseCase qaMerge;

  @MockitoBean dev.platform.reading.tuvi.application.number.NumberCheckService numberCheck;

  private ResultActions qaGet(String path) throws Exception {
    return mvc.perform(
        get(path).header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer s"));
  }

  @Test
  void qa_list_matches_the_spec_schema() throws Exception {
    when(qa.list("s"))
        .thenReturn(
            List.of(
                new ReadingSummary(
                    QA_ID,
                    QaKind.PAID,
                    QaTopic.TaiLoc,
                    QaStatus.ANSWERED,
                    Instant.parse("2026-11-25T03:00:00Z"),
                    2)));

    JsonNode json = JsonMapper.shared().readTree(okBody(qaGet("/tuvi/qa/readings")));

    assertThat(ContractSupport.componentSchema("QaReadingList").validate(json)).isEmpty();
    assertThat(json.get("readings").get(0).get("reading_id").asString())
        .isEqualTo(QA_ID.toString());
  }

  @Test
  void qa_reading_matches_the_spec_schema() throws Exception {
    Instant at = Instant.parse("2026-11-25T03:00:00Z");
    when(qa.get("s", QA_ID))
        .thenReturn(
            new ReadingDetail(
                QA_ID,
                QaKind.PAID,
                QaTopic.TaiLoc,
                QaStatus.RETRYING,
                at,
                at.plusSeconds(86400),
                2,
                "Ta đang ngẫm.",
                "Lời nhắc.",
                List.of(new ReadingDetail.Message(0, "Tài lộc ra sao", null, at))));

    JsonNode json = JsonMapper.shared().readTree(okBody(qaGet("/tuvi/qa/readings/" + QA_ID)));

    assertThat(ContractSupport.componentSchema("QaReading").validate(json)).isEmpty();
    assertThat(json.get("messages").get(0).get("answer").isNull()).isTrue();

    when(qa.get("s", QA_ID))
        .thenReturn(
            new ReadingDetail(
                QA_ID,
                QaKind.FREE,
                QaTopic.TaiLoc,
                QaStatus.ANSWERED,
                at,
                null,
                0,
                null,
                "Lời nhắc.",
                List.of(new ReadingDetail.Message(0, "Tài lộc ra sao", "Con à.", at))));
    JsonNode answered = JsonMapper.shared().readTree(okBody(qaGet("/tuvi/qa/readings/" + QA_ID)));
    assertThat(ContractSupport.componentSchema("QaReading").validate(answered)).isEmpty();
  }

  @Test
  void qa_free_quota_and_conflict_match_the_spec_schema() throws Exception {
    when(qa.freeQuota("s")).thenReturn(new FreeQuota(3, 3, 0));

    JsonNode json = JsonMapper.shared().readTree(okBody(qaGet("/tuvi/qa/free-quota")));

    assertThat(ContractSupport.componentSchema("QaFreeQuota").validate(json)).isEmpty();

    when(qa.list("s")).thenThrow(new QaConflictException("reading_in_progress"));
    JsonNode problem = problem(qaGet("/tuvi/qa/readings"), 409);
    assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    assertThat(problem.get("reason").asString()).isEqualTo("reading_in_progress");
  }

  @Test
  void qa_merge_matches_the_spec_schema() throws Exception {
    UUID guest = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    when(qaMerge.merge(guest, account)).thenReturn(2);
    String body = "{\"guest_id\":\"" + guest + "\",\"account_id\":\"" + account + "\"}";

    JsonNode json =
        JsonMapper.shared()
            .readTree(
                okBody(
                    mvc.perform(
                        post("/internal/qa/merge")
                            .header("X-Internal-Token", INTERNAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))));

    assertThat(ContractSupport.componentSchema("MergeResult").validate(json)).isEmpty();
    assertThat(json.get("moved").asInt()).isEqualTo(2);
    assertThat(
            ContractSupport.componentSchema("MergeRequest")
                .validate(JsonMapper.shared().readTree(body)))
        .isEmpty();
    JsonNode bad =
        problem(
            mvc.perform(
                post("/internal/qa/merge")
                    .header("X-Internal-Token", INTERNAL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"guest_id\":\"x\",\"account_id\":\"" + account + "\"}")),
            400);
    assertThat(ContractSupport.componentSchema("Problem").validate(bad)).isEmpty();
    assertThat(bad.get("errors").get(0).get("field").asString()).isEqualTo("guest_id");
  }

  @Test
  void qa_schemas_are_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"readings\":[{}],\"limit\":-1}");
    for (String s : List.of("QaReadingList", "QaReading", "QaFreeQuota", "QaAskRequest")) {
      assertThat(ContractSupport.componentSchema(s).validate(bad)).as(s).isNotEmpty();
    }
    JsonNode ask =
        JsonMapper.shared()
            .readTree(
                "{\"reading_id\":\""
                    + QA_ID
                    + "\",\"kind\":\"PAID\",\"topic\":\"TaiLoc\",\"question\":\"Tài lộc?\","
                    + "\"lang\":\"vi\"}");
    assertThat(ContractSupport.componentSchema("QaAskRequest").validate(ask)).isEmpty();
  }

  private static String okBody(ResultActions result) throws Exception {
    var response = result.andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  static final String BODY =
      "{\"birth\":\"1994-03-12T08:30\",\"zone\":\"Asia/Ho_Chi_Minh\",\"gender\":\"male\","
          + "\"calendar\":\"VN\"%s}";

  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerMapping mappings;
  @MockitoBean BasicReadingUseCase basic;
  @MockitoBean TodayUseCase today;

  @Test
  void every_path_and_method_in_the_spec_is_served() {
    Set<String> served = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
      for (RequestMethod m : e.getKey().getMethodsCondition().getMethods()) {
        e.getKey().getPatternValues().forEach(p -> served.add(m + " " + p));
      }
    }
    Set<String> declared = new HashSet<>();
    JsonNode paths = ContractSupport.openApi().get("paths");
    for (String path : paths.propertyNames()) {
      for (String method : paths.get(path).propertyNames()) {
        declared.add(method.toUpperCase(Locale.ROOT) + " " + path);
      }
    }

    assertThat(declared)
        .containsExactlyInAnyOrder(
            "POST /tuvi/readings/basic",
            "POST /tuvi/readings/today",
            "POST /tuvi/qa/readings",
            "GET /tuvi/qa/readings",
            "DELETE /tuvi/qa/readings",
            "GET /tuvi/qa/readings/{id}",
            "POST /tuvi/qa/readings/{id}/follow-ups",
            "GET /tuvi/qa/free-quota",
            "POST /internal/qa/merge",
            "POST /tuvi/number-check",
            "GET /tuvi/number-check/quota");
    assertThat(served).containsAll(declared);
  }

  @Test
  void basic_response_matches_the_spec_schema() throws Exception {
    List<BasicReading.RenderedSection> sections =
        IntStream.range(0, 17)
            .mapToObj(
                i ->
                    new BasicReading.RenderedSection(
                        "s" + i, i % 2 == 0 ? null : "Tiêu đề", List.of("dòng")))
            .toList();
    when(basic.basic(any(Birth.class)))
        .thenReturn(new BasicReading("LaoThienCo", "iztro-default", "a".repeat(64), 1, sections));

    JsonNode json = ok(basicRequest());

    assertThat(ContractSupport.componentSchema("BasicReading").validate(json)).isEmpty();
  }

  @Test
  void today_response_matches_the_spec_schema() throws Exception {
    List<TodayReading.AreaLine> areas =
        List.of(
            new TodayReading.AreaLine(Area.CongViec, "Công việc", Level.Thuan, "a"),
            new TodayReading.AreaLine(Area.DiLai, "Đi lại", Level.Binh, "b"),
            new TodayReading.AreaLine(Area.TienBac, "Tiền bạc", Level.LuuY, "c"),
            new TodayReading.AreaLine(Area.SucKhoe, "Sức khỏe", Level.Binh, "d"));
    when(today.today(any(Birth.class), any(LocalDate.class)))
        .thenReturn(
            new TodayReading(
                LocalDate.of(2026, 11, 24), "15/10", "Giáp Tý", "LaoThienCo", "o", areas, "c"));

    JsonNode json =
        ok(
            post("/tuvi/readings/today")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(",\"date\":\"2026-11-24\"")));

    assertThat(ContractSupport.componentSchema("TodayReading").validate(json)).isEmpty();
  }

  @Test
  void schema_checks_are_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"character\":\"X\",\"sections\":[]}");
    assertThat(ContractSupport.componentSchema("BasicReading").validate(bad)).isNotEmpty();
    assertThat(ContractSupport.componentSchema("TodayReading").validate(bad)).isNotEmpty();
    JsonNode noType = JsonMapper.shared().readTree("{\"title\":\"x\",\"status\":503}");
    assertThat(ContractSupport.componentSchema("Problem").validate(noType)).isNotEmpty();
  }

  @Test
  void malformed_body_problem_matches_the_spec_schema() throws Exception {
    JsonNode problem =
        problem(
            mvc.perform(
                post("/tuvi/readings/basic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{nope")),
            400);

    assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("body");
  }

  @Test
  void upstream_rejection_problem_matches_the_spec_schema() throws Exception {
    when(basic.basic(any(Birth.class)))
        .thenThrow(
            new UpstreamValidationException(
                List.of(new UpstreamValidationException.FieldError("birth", "invalid"))));

    JsonNode problem = problem(mvc.perform(basicRequest()), 400);

    assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    assertThat(problem.get("type").asString()).isEqualTo("urn:problem:validation-error");
  }

  @Test
  void unavailable_problem_matches_the_spec_schema() throws Exception {
    when(basic.basic(any(Birth.class))).thenThrow(new UpstreamUnavailableException("x"));

    JsonNode problem = problem(mvc.perform(basicRequest()), 503);

    assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
    assertThat(problem.get("type").asString()).isEqualTo("urn:problem:upstream-unavailable");
  }

  private static String basic() {
    return "/tuvi/readings/basic";
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      basicRequest() {
    return post(basic()).contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(""));
  }

  private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
      throws Exception {
    var response = mvc.perform(b).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return JsonMapper.shared().readTree(response.getContentAsString());
  }

  private static JsonNode problem(ResultActions result, int status) throws Exception {
    var response = result.andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    return JsonMapper.shared().readTree(response.getContentAsString());
  }
}
