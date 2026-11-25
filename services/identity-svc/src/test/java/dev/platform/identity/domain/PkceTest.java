package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

  @Test
  void challenge_matches_the_rfc_7636_appendix_b_example() {
    assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  }

  @Test
  void random_values_are_43_url_safe_characters_and_differ() {
    String a = Pkce.randomValue(new java.security.SecureRandom());
    String b = Pkce.randomValue(new java.security.SecureRandom());

    assertThat(a).matches("[A-Za-z0-9_-]{43}");
    assertThat(a).isNotEqualTo(b);
  }
}
