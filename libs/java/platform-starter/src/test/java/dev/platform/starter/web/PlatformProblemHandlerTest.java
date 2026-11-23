package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.starter.testapp.TestApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class PlatformProblemHandlerTest {

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
  void trace_id_taken_from_mdc() throws Exception {
    mvc.perform(get("/boom/not-found").header("X-Test-Trace", "abc"))
        .andExpect(jsonPath("$.trace_id").value("abc"));
  }

  @Test
  void trace_id_is_empty_string_without_mdc() throws Exception {
    mvc.perform(get("/boom/not-found")).andExpect(jsonPath("$.trace_id").value(""));
  }
}
