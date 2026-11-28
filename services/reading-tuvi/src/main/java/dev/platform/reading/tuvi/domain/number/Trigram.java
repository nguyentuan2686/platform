package dev.platform.reading.tuvi.domain.number;

/**
 * The eight trigrams in Tiên thiên (Fu Xi) numbering, as Mai Hoa uses it: 1 Càn, 2 Đoài, 3 Ly, 4
 * Chấn, 5 Tốn, 6 Khảm, 7 Cấn, 8 Khôn. {@code east} is the Bát trạch group: Khảm, Chấn, Tốn, Ly are
 * Đông tứ, the rest Tây tứ. {@code image} is the nature name used in hexagram names.
 */
public enum Trigram {
  CAN_THIEN(1, "Càn", "Thiên", false),
  DOAI(2, "Đoài", "Trạch", false),
  LY(3, "Ly", "Hỏa", true),
  CHAN(4, "Chấn", "Lôi", true),
  TON(5, "Tốn", "Phong", true),
  KHAM(6, "Khảm", "Thủy", true),
  CAN_SON(7, "Cấn", "Sơn", false),
  KHON(8, "Khôn", "Địa", false);

  private final int number;
  private final String label;
  private final String image;
  private final boolean east;

  Trigram(int number, String label, String image, boolean east) {
    this.number = number;
    this.label = label;
    this.image = image;
    this.east = east;
  }

  public static Trigram ofNumber(int n) {
    for (Trigram t : values()) {
      if (t.number == n) {
        return t;
      }
    }
    throw new IllegalArgumentException("trigram number must be 1..8");
  }

  public int number() {
    return number;
  }

  public String label() {
    return label;
  }

  public String image() {
    return image;
  }

  public boolean east() {
    return east;
  }
}
