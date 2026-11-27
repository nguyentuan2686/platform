package dev.platform.__JAVA_PKG__;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.platform.__JAVA_PKG__.adapter.in.web.InfoController;
import dev.platform.__JAVA_PKG__.application.GetInfoUseCase;
import dev.platform.__JAVA_PKG__.domain.ServiceInfo;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(InfoController.class)
// Fully qualified, not imported: an import of dev.platform.starter would sort above or below this
// service's own dev.platform.<pkg> imports depending on its name, and fail Spotless for some names.
@Import(dev.platform.starter.web.PlatformWebAutoConfiguration.class)
class ContractTest {

  static final String NAME = "__SERVICE__";

  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerMapping mappings;
  @MockitoBean GetInfoUseCase getInfo;

  @Test
  void every_path_and_method_in_the_spec_is_served() {
    Set<String> served = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
      Set<String> patterns = e.getKey().getPatternValues();
      for (RequestMethod m : e.getKey().getMethodsCondition().getMethods()) {
        patterns.forEach(p -> served.add(m + " " + p));
      }
    }

    Set<String> declared = new HashSet<>();
    JsonNode paths = ContractSupport.openApi().get("paths");
    for (String path : paths.propertyNames()) {
      if (path.startsWith("/health/")) {
        continue; // served by the actuator, checked by HealthIT
      }
      for (String method : paths.get(path).propertyNames()) {
        declared.add(method.toUpperCase(Locale.ROOT) + " " + path);
      }
    }

    assertThat(declared).containsExactlyInAnyOrder("GET /v1/info");
    assertThat(served).containsAll(declared);
  }

  @Test
  void info_response_matches_the_spec_schema() throws Exception {
    when(getInfo.info()).thenReturn(new ServiceInfo(NAME));

    String body = mvc.perform(get("/v1/info")).andReturn().getResponse().getContentAsString();

    JsonNode json = JsonMapper.shared().readTree(body);
    assertThat(ContractSupport.componentSchema("Info").validate(json)).isEmpty();
  }

  @Test
  void schema_check_is_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"service\":\"\"}");
    assertThat(ContractSupport.componentSchema("Info").validate(bad)).isNotEmpty();
  }

  @Test
  void not_found_problem_matches_the_spec_schema() throws Exception {
    var response = mvc.perform(get("/v1/nope")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    JsonNode problem = JsonMapper.shared().readTree(response.getContentAsString());
    assertThat(ContractSupport.componentSchema("Problem").validate(problem)).isEmpty();
  }

  @Test
  void problem_schema_check_is_not_vacuous() {
    JsonNode noType = JsonMapper.shared().readTree("{\"title\":\"x\",\"status\":404}");
    assertThat(ContractSupport.componentSchema("Problem").validate(noType)).isNotEmpty();
  }
}
