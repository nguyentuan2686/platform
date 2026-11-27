package dev.platform.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class HmacClientKeyerTest {

  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void the_key_is_a_stable_keyed_hash_that_never_shows_the_address() {
    HmacClientKeyer keyer = new HmacClientKeyer(KEY);
    String key = keyer.key("v4:203.0.113.7").value();

    assertThat(key).matches("[0-9a-f]{64}").doesNotContain("203");
    assertThat(keyer.key("v4:203.0.113.7").value()).isEqualTo(key);
    assertThat(keyer.key("v4:203.0.113.8").value()).isNotEqualTo(key);
    assertThat(keyer.key("v4:203.0.113.7").toString()).doesNotContain(key);
  }

  @Test
  void the_key_depends_on_the_secret() {
    byte[] other = new byte[32];
    other[0] = 1;
    assertThat(new HmacClientKeyer(Base64.getEncoder().encodeToString(other)).key("v4:203.0.113.7"))
        .isNotEqualTo(new HmacClientKeyer(KEY).key("v4:203.0.113.7"));
  }

  @Test
  void refuses_a_missing_or_malformed_key() {
    for (String bad :
        new String[] {null, "", "not base64!", Base64.getEncoder().encodeToString(new byte[16])}) {
      assertThatThrownBy(() -> new HmacClientKeyer(bad)).isInstanceOf(IllegalStateException.class);
    }
  }
}
