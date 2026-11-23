package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.starter.testapp.TestApp;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class PlatformProblemHandlerTest {

  static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

  @Autowired MockMvc mvc;

  @Test
  void not_found_maps_to_404_problem() throws Exception {
    mvc.perform(get("/boom/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("order x not found"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void validation_maps_to_400_with_field_errors() throws Exception {
    mvc.perform(
            post("/boom/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void nested_list_validation_uses_indexed_field_path() throws Exception {
    mvc.perform(
            post("/boom/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"items\":[{\"quantity\":0}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"));
  }

  @Test
  void malformed_json_maps_to_400() throws Exception {
    mvc.perform(post("/boom/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void unexpected_exception_is_500_without_leak() throws Exception {
    String body =
        mvc.perform(get("/boom/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Unexpected error"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("secret", "RuntimeException", "at dev.");
  }

  @Test
  void trace_id_taken_from_current_span_not_mdc() throws Exception {
    // The OTel agent makes the server span current but never calls MDC.put.
    assertThat(MDC.get("trace_id")).isNull();
    mvc.perform(get("/boom/not-found").header("X-Test-Trace", TRACE))
        .andExpect(jsonPath("$.trace_id").value(TRACE));
  }

  @Test
  void trace_id_taken_from_current_span_for_framework_handled_errors() throws Exception {
    mvc.perform(
            post("/boom/validate")
                .header("X-Test-Trace", TRACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(jsonPath("$.trace_id").value(TRACE));
  }

  @Test
  void problem_type_is_urn_slug() throws Exception {
    mvc.perform(get("/boom/not-found"))
        .andExpect(jsonPath("$.type").value("urn:problem:not-found"));
    mvc.perform(post("/boom/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"));
    mvc.perform(
            post("/boom/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"));
    mvc.perform(get("/boom/unexpected"))
        .andExpect(jsonPath("$.type").value("urn:problem:internal-error"));
  }

  @Test
  void trace_id_is_empty_string_without_span() throws Exception {
    mvc.perform(get("/boom/not-found")).andExpect(jsonPath("$.trace_id").value(""));
  }
}
