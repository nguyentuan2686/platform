package dev.platform.__JAVA_PKG__.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.__JAVA_PKG__.application.GetInfoUseCase;
import dev.platform.__JAVA_PKG__.domain.ServiceInfo;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InfoController.class)
@Import(PlatformWebAutoConfiguration.class)
class InfoControllerTest {

  static final String NAME = "__SERVICE__";

  @Autowired MockMvc mvc;
  @MockitoBean GetInfoUseCase getInfo;

  @Test
  void info_returns_the_service_name() throws Exception {
    when(getInfo.info()).thenReturn(new ServiceInfo(NAME));

    mvc.perform(get("/v1/info"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.service").value(NAME));
  }

  @Test
  void wrong_method_is_a_problem_with_trace_id() throws Exception {
    mvc.perform(post("/v1/info"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.trace_id").exists());
  }
}
