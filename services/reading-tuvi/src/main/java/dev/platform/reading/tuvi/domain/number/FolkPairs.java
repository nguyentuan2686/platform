package dev.platform.reading.tuvi.domain.number;

import java.util.List;

/**
 * Folk digit sequences (spec §5.3), the builder's pick of the readings most Vietnamese sites share.
 * Good: 39 and 79 (thần tài), 38 and 78 (ông địa), 68 and 86 (lộc phát), 66, 88, 99 (doubles read
 * as lộc, phát, trường cửu), 6789 (the rising run). Avoid: 49 and 53 (the folk "bốn chín chưa qua,
 * năm ba đã tới" saying). Each entry has a knowledge record {@code so:pair=<entry>}.
 */
public final class FolkPairs {

  public static final List<String> GOOD =
      List.of("39", "79", "38", "78", "68", "86", "66", "88", "99", "6789");

  public static final List<String> AVOID = List.of("49", "53");

  private FolkPairs() {}

  /** Every entry, good first. */
  public static List<String> all() {
    return java.util.stream.Stream.concat(GOOD.stream(), AVOID.stream()).toList();
  }
}
