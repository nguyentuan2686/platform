package dev.platform.reading.tuvi.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HmacNumberHasherTest {

  static final String KEY = "local-test-number-check-hmac-key";

  @Test
  void ipv6_in_one_64_hashes_equal_and_ipv4_is_unchanged() {
    HmacNumberHasher h = new HmacNumberHasher(KEY);
    assertThat(h.ipHash("2001:db8:1:2:aaaa::1")).isEqualTo(h.ipHash("2001:db8:1:2:bbbb::2"));
    assertThat(h.ipHash("2001:db8:1:2:aaaa::1")).isNotEqualTo(h.ipHash("2001:db8:1:3::1"));
    assertThat(HmacNumberHasher.ipMaterial("203.0.113.7")).isEqualTo("203.0.113.7");
    assertThat(HmacNumberHasher.ipMaterial(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
    assertThat(HmacNumberHasher.ipMaterial("2001:db8:1:2:aaaa::1"))
        .isEqualTo("v6:20010db800010002");
    assertThat(h.ipHash("203.0.113.7")).isNotEqualTo(h.ipHash("203.0.113.8"));
    // not a valid literal: hashed as given, no lookup
    assertThat(HmacNumberHasher.ipMaterial("1:2:3:4:5:6:7:8:9")).isEqualTo("1:2:3:4:5:6:7:8:9");
  }

  @Test
  void check_hash_is_a_keyed_sha256() {
    HmacNumberHasher h = new HmacNumberHasher(KEY);
    assertThat(h.checkHash("sim|0912345678")).hasSize(32).isEqualTo(h.checkHash("sim|0912345678"));
    assertThat(h.checkHash("sim|0912345678"))
        .isNotEqualTo(new HmacNumberHasher(KEY + "x").checkHash("sim|0912345678"))
        .isNotEqualTo(h.checkHash("bank|0912345678"));
  }

  @Test
  void a_missing_or_short_key_fails_startup_without_echoing_it() {
    assertThatThrownBy(() -> new HmacNumberHasher(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NUMBER_CHECK_HMAC_KEY");
    assertThatThrownBy(() -> new HmacNumberHasher(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HmacNumberHasher("short-key"))
        .hasMessageNotContaining("short-key");
  }
}
