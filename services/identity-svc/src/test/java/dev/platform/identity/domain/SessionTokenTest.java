package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SessionTokenTest {

  private final SecureRandom random = new SecureRandom();

  @Test
  void generated_tokens_differ_and_are_43_url_safe_chars() {
    SessionToken a = SessionToken.generate(random);
    SessionToken b = SessionToken.generate(random);

    assertThat(a.value()).isNotEqualTo(b.value()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
  }

  @Test
  void sha256_is_stable_and_32_bytes() {
    SessionToken a = SessionToken.generate(random);

    assertThat(a.sha256()).hasSize(32).isEqualTo(a.sha256());
    assertThat(SessionToken.sha256(a.value())).isEqualTo(a.sha256());
    assertThat(Arrays.equals(a.sha256(), SessionToken.generate(random).sha256())).isFalse();
  }

  @Test
  void parse_accepts_only_well_formed_tokens() {
    SessionToken a = SessionToken.generate(random);

    assertThat(SessionToken.parse(a.value())).contains(a);
    assertThat(SessionToken.parse("short")).isEmpty();
    assertThat(SessionToken.parse(null)).isEmpty();
    assertThat(SessionToken.parse("!".repeat(43))).isEmpty();
  }

  @Test
  void to_string_is_redacted() {
    SessionToken a = SessionToken.generate(random);

    assertThat(a.toString()).doesNotContain(a.value());
    assertThatThrownBy(() -> new SessionToken(null)).isInstanceOf(NullPointerException.class);
  }
}
