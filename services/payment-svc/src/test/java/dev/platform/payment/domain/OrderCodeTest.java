package dev.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderCodeTest {

  @Test
  void tuvi_codes_have_prefix_and_unambiguous_alphabet() {
    SecureRandom random = new SecureRandom();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      String code = OrderCode.generate("tuvi", random).value();
      assertThat(code).matches("^TV[2-9A-HJKMNP-Z]{6}$");
      seen.add(code);
    }
    assertThat(seen.size()).isGreaterThan(9_900);
  }

  @Test
  void astro_codes_use_the_CT_prefix() {
    assertThat(OrderCode.generate("astro", new SecureRandom()).value())
        .matches("^CT[2-9A-HJKMNP-Z]{6}$");
  }

  @Test
  void unknown_site_is_rejected() {
    assertThatThrownBy(() -> OrderCode.generate("nope", new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
