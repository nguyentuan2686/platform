package dev.platform.__JAVA_PKG__.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.__JAVA_PKG__.application.GetInfoUseCase;
import dev.platform.__JAVA_PKG__.domain.ServiceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InfoController.class)
// Fully qualified, not imported: an import of dev.platform.starter would sort above or below this
// service's own dev.platform.<pkg> imports depending on its name, and fail Spotless for some names.
@Import(dev.platform.starter.web.PlatformWebAutoConfiguration.class)
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
