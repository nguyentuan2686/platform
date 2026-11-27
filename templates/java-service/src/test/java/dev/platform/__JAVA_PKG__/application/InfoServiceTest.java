package dev.platform.__JAVA_PKG__.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.__JAVA_PKG__.domain.ServiceInfo;
import org.junit.jupiter.api.Test;

class InfoServiceTest {

  static final String NAME = "__SERVICE__";

  @Test
  void info_names_the_service() {
    assertThat(new InfoService(NAME).info()).isEqualTo(new ServiceInfo(NAME));
  }

  @Test
  void a_blank_name_fails_at_startup() {
    assertThatThrownBy(() -> new InfoService("")).isInstanceOf(IllegalArgumentException.class);
  }
}
