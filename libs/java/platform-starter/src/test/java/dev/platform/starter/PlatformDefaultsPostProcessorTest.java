package dev.platform.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlatformDefaultsPostProcessorTest {

  private static final String KEY = "logging.structured.format.console";

  @Test
  void registers_structured_log_format_default() {
    MockEnvironment env = new MockEnvironment();

    new PlatformDefaultsPostProcessor().postProcessEnvironment(env, null);

    assertThat(env.getProperty(KEY)).isEqualTo("dev.platform.starter.logging.PlatformLogFormatter");
  }

  @Test
  void ready_group_lists_db_without_membership_validation() {
    MockEnvironment env = new MockEnvironment();

    new PlatformDefaultsPostProcessor().postProcessEnvironment(env, null);

    assertThat(env.getProperty("management.endpoint.health.group.ready.include"))
        .isEqualTo("readinessState,db");
    assertThat(env.getProperty("management.endpoint.health.validate-group-membership"))
        .isEqualTo("false");
  }

  @Test
  void service_configuration_overrides_defaults() {
    MockEnvironment env = new MockEnvironment().withProperty(KEY, "logstash");

    new PlatformDefaultsPostProcessor().postProcessEnvironment(env, null);

    assertThat(env.getProperty(KEY)).isEqualTo("logstash");
  }
}
