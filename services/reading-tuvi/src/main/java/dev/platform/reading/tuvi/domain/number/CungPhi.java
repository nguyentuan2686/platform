package dev.platform.reading.tuvi.domain.number;

/**
 * Cung phi (Bát trạch) of a lunar year and gender (spec §5.2). Numbers follow the Lạc Thư square: 1
 * Khảm, 2 Khôn, 3 Chấn, 4 Tốn, 6 Càn, 7 Đoài, 8 Cấn, 9 Ly; 5 becomes Khôn for men, Cấn for women.
 */
public final class CungPhi {

  private CungPhi() {}

  /** The cung phi number (never 5) for a lunar year. */
  public static int of(int lunarYear, Gender gender) {
    int lastTwo = Math.floorMod(lunarYear, 100);
    int r = reduce(lastTwo / 10 + lastTwo % 10);
    int n;
    if (lunarYear < 2000) {
      n = gender == Gender.MALE ? reduce(10 - r) : reduce(5 + r);
    } else {
      n = gender == Gender.MALE ? 9 - r : reduce(6 + r);
      if (n == 0) {
        n = 9;
      }
    }
    if (n == 5) {
      n = gender == Gender.MALE ? 2 : 8;
    }
    return n;
  }

  public static String name(int cungPhi) {
    return switch (cungPhi) {
      case 1 -> "Khảm";
      case 2 -> "Khôn";
      case 3 -> "Chấn";
      case 4 -> "Tốn";
      case 6 -> "Càn";
      case 7 -> "Đoài";
      case 8 -> "Cấn";
      case 9 -> "Ly";
      default -> throw new IllegalArgumentException("no such cung phi");
    };
  }

  /** Đông tứ mệnh: Khảm, Chấn, Tốn, Ly; the rest are Tây tứ mệnh. */
  public static boolean isEast(int cungPhi) {
    return cungPhi == 1 || cungPhi == 3 || cungPhi == 4 || cungPhi == 9;
  }

  /** Repeated digit sum down to one digit (0 stays 0). */
  static int reduce(int n) {
    int v = n;
    while (v > 9) {
      int s = 0;
      for (int x = v; x > 0; x /= 10) {
        s += x % 10;
      }
      v = s;
    }
    return v;
  }
}
