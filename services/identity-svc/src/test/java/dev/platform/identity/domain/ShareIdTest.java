package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class ShareIdTest {

  @Test
  void random_is_22_url_safe_characters_and_unique() {
    SecureRandom random = new SecureRandom();

    ShareId a = ShareId.random(random);
    ShareId b = ShareId.random(random);

    assertThat(a.value()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
    assertThat(a).isNotEqualTo(b);
    assertThat(ShareId.parse(a.value())).isEqualTo(a);
  }

  @Test
  void parse_rejects_anything_else() {
    assertThatThrownBy(() -> ShareId.parse("bad")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ShareId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ShareId.parse("a".repeat(21) + "+"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ShareId.parse("a".repeat(23)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
