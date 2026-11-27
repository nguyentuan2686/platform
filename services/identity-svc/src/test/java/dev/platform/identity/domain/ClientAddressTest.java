package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

  private static Optional<String> n(String raw) {
    return ClientAddress.normalize(raw);
  }

  @Test
  void ipv4_counts_on_the_whole_address() {
    assertThat(n("203.0.113.7")).hasValue("v4:203.0.113.7");
    assertThat(n(" 203.0.113.7 ")).hasValue("v4:203.0.113.7");
    assertThat(n("203.0.113.8")).isNotEqualTo(n("203.0.113.7"));
  }

  @Test
  void ipv6_counts_on_the_64_bit_prefix_whatever_the_spelling() {
    assertThat(n("2001:db8:1:2::1")).hasValue("v6:2001:db8:1:2");
    assertThat(n("2001:0DB8:0001:0002:ffff:eeee:dddd:cccc")).hasValue("v6:2001:db8:1:2");
    assertThat(n("2001:db8:1:2:0:0:0:9")).hasValue("v6:2001:db8:1:2");
    assertThat(n("2001:db8:1:3::1")).hasValue("v6:2001:db8:1:3");
    assertThat(n("::1")).hasValue("v6:0:0:0:0");
    assertThat(n("2001:db8::")).hasValue("v6:2001:db8:0:0");
    assertThat(n("1:2:3:4:5:6:7::")).hasValue("v6:1:2:3:4");
  }

  @Test
  void ipv4_mapped_ipv6_is_the_ipv4_address() {
    assertThat(n("::ffff:203.0.113.7")).hasValue("v4:203.0.113.7");
  }

  @Test
  void anything_else_is_not_a_client_address() {
    for (String bad :
        new String[] {
          null,
          "",
          "   ",
          "localhost",
          "203.0.113",
          "203.0.113.256",
          "203.0.113.07x",
          "1.2.3.4.5",
          ":::",
          "2001:db8::1::2",
          "2001:db8:1:2:3:4:5:6:7",
          "1:2:3:4:5:6:7:8::",
          "2001:db8:12345::",
          "fe80::1%eth0",
          "203.0.113.7, 198.51.100.1",
          "x".repeat(100)
        }) {
      assertThat(n(bad)).as(String.valueOf(bad)).isEmpty();
    }
  }
}
