package dev.platform.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.starter.testapp.TestApp;
import java.net.URI;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class InternalTokenFilterTest {

  private static final String TOKEN = "local-test-internal-token";

  @Nested
  @SpringBootTest(
      classes = TestApp.class,
      properties = {"platform.internal-token=" + TOKEN})
  @AutoConfigureMockMvc
  class WithToken {

    @Autowired MockMvc mvc;

    @Test
    void missing_header_is_401_problem_with_trace_id() throws Exception {
      mvc.perform(get("/boom/not-found"))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"))
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.detail").value("Missing or invalid internal token"))
          .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void wrong_header_is_401() throws Exception {
      mvc.perform(get("/boom/not-found").header("X-Internal-Token", "nope"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.type").value("urn:problem:unauthenticated"));
    }

    @Test
    void right_header_passes_through() throws Exception {
      // The controller throws NotFoundException -> 404 proves the request got past the filter.
      mvc.perform(get("/boom/not-found").header("X-Internal-Token", TOKEN))
          .andExpect(status().isNotFound());
    }

    @Test
    void health_is_open_without_header() throws Exception {
      mvc.perform(get("/health/live")).andExpect(status().isOk());
    }

    @Test
    void traversal_out_of_open_path_is_401() throws Exception {
      for (String path :
          new String[] {
            "/health/../boom/not-found",
            "/health/..;/boom/not-found",
            "/health/%2e%2e/boom/not-found"
          }) {
        mvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = TestApp.class,
      properties = {
        "platform.internal-token=" + TOKEN,
        "platform.internal-token.open-paths=/health/**,/boom/not-found"
      })
  @AutoConfigureMockMvc
  class WithCustomOpenPaths {

    @Autowired MockMvc mvc;

    @Test
    void configured_open_path_needs_no_header() throws Exception {
      mvc.perform(get("/boom/not-found")).andExpect(status().isNotFound());
    }

    @Test
    void other_paths_still_protected() throws Exception {
      mvc.perform(get("/boom/unexpected")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @SpringBootTest(classes = TestApp.class)
  @AutoConfigureMockMvc
  class WithoutToken {

    @Autowired MockMvc mvc;

    @Test
    void no_header_needed_when_token_unset() throws Exception {
      mvc.perform(get("/boom/not-found")).andExpect(status().isNotFound());
    }
  }
}
