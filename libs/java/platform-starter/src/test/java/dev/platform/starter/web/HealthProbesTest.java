package dev.platform.starter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.starter.testapp.TestApp;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class HealthProbesTest {

  @Autowired MockMvc mvc;
  @Autowired Environment environment;
  @Autowired ApplicationContext context;

  @Test
  void ready_probe_is_ok_without_datasource_although_ready_group_lists_db() throws Exception {
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    assertThat(environment.getProperty("management.endpoint.health.group.ready.include"))
        .isEqualTo("readinessState,db");

    mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

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
