package dev.platform.__JAVA_PKG__.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServiceInfoTest {

  // A constant, not literals: formatting must not depend on the length of the name.
  static final String NAME = "__SERVICE__";

  @Test
  void keeps_the_name() {
    assertThat(new ServiceInfo(NAME).service()).isEqualTo(NAME);
  }

  @Test
  void rejects_a_blank_or_missing_name() {
    assertThatThrownBy(() -> new ServiceInfo(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ServiceInfo(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
