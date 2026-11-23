package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.starter.testapp.TestApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApp.class, properties = TestApp.NO_DATABASE_READY_GROUP)
@AutoConfigureMockMvc
class HealthProbesTest {

  @Autowired MockMvc mvc;
  @Autowired Environment environment;

  @Test
  void live_probe_is_up() throws Exception {
    mvc.perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void ready_probe_is_ok() throws Exception {
    mvc.perform(get("/health/ready")).andExpect(status().isOk());
  }

  @Test
  void booted_app_picks_up_platform_log_format_via_spring_factories() {
    assertThat(environment.getProperty("logging.structured.format.console"))
        .isEqualTo("dev.platform.starter.logging.PlatformLogFormatter");
  }
}
