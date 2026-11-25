package dev.platform.payment.domain;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The reference the customer puts in the bank transfer: site prefix plus 6 characters without the
 * easily confused 0, O, 1, I, L.
 */
public record OrderCode(String value) {

  static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  static final int LENGTH = 6;

  public OrderCode {
    Objects.requireNonNull(value, "value");
  }

  public static OrderCode generate(String site, RandomGenerator random) {
    String prefix =
        switch (site) {
          case "tuvi" -> "TV";
          case "astro" -> "CT";
          default -> throw new IllegalArgumentException("unknown site");
        };
    StringBuilder code = new StringBuilder(prefix);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return new OrderCode(code.toString());
  }
}
