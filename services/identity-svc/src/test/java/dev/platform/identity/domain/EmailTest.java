package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

  @Test
  void trims_and_lowercases() {
    assertThat(new Email(" Foo@Example.COM ").value()).isEqualTo("foo@example.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "a", "a@", "@b", "a@b@c", "a b@c.com"})
  void rejects_malformed(String raw) {
    assertThatThrownBy(() -> new Email(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_null() {
    assertThatThrownBy(() -> new Email(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_255_char_address_and_accepts_254() {
    String tail = "@example.com";
    assertThat(new Email("a".repeat(254 - tail.length()) + tail).value()).hasSize(254);
    assertThatThrownBy(() -> new Email("a".repeat(255 - tail.length()) + tail))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void to_string_is_redacted() {
    assertThat(new Email("foo@example.com").toString()).doesNotContain("@", "foo");
  }

  @Test
  void masks_for_display() {
    assertThat(new Email("tuan@gmail.com").masked()).isEqualTo("t***@g***.com");
    assertThat(new Email("a@b.co.uk").masked()).isEqualTo("a***@b***.uk");
    assertThat(new Email("a@localhost").masked()).isEqualTo("a***@l***");
  }
}
