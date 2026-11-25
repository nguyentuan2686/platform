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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a,b@x.com",
        "a@x.com,b@y.com",
        "a@x.com b@y.com",
        "a;b@x.com",
        "\"a\"@x.com",
        "<a@x.com>",
        "Name <a@x.com>",
        "a@x.com\r\nBcc: v@y.com",
        "a\u0000@x.com",
        "a@x\u0007.com",
        "a(c)@x.com",
        "a\\b@x.com",
        ".a@x.com",
        "a.@x.com",
        "a..b@x.com",
        "a@.x.com",
        "a@x..com",
        "a@-x.com",
        "a@x-.com",
        "a@[127.0.0.1]",
        "a@x_y.com",
        "tên@x.com",
        "a@münchen.de"
      })
  void rejects_unsafe_shapes(String raw) {
    assertThatThrownBy(() -> new Email(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accepts_common_addresses() {
    assertThat(new Email("first.last+tag@sub.example.co.uk").value())
        .isEqualTo("first.last+tag@sub.example.co.uk");
    assertThat(new Email("o'brien@example.com").value()).isEqualTo("o'brien@example.com");
  }

  @Test
  void rejects_local_part_over_64_and_total_over_254() {
    assertThat(new Email("a".repeat(64) + "@example.com").value()).hasSize(76);
    assertThatThrownBy(() -> new Email("a".repeat(65) + "@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    String label = "b".repeat(63);
    String ok = "a@" + label + "." + label + "." + label + "." + "c".repeat(60); // 254
    assertThat(ok).hasSize(254);
    assertThat(new Email(ok).value()).hasSize(254);
    assertThatThrownBy(() -> new Email(ok + "c")).isInstanceOf(IllegalArgumentException.class);
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
