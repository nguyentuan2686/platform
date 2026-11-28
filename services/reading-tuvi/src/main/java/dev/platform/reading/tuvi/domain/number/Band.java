package dev.platform.reading.tuvi.domain.number;

import java.util.Locale;

/** Score band of a rounded total: ≥ 8, 6.5–7.5, 5–6, below 5. */
public enum Band {
  RAT_HOP("Rất hợp"),
  KHA_HOP("Khá hợp"),
  BINH_THUONG("Bình thường"),
  CHUA_HOP("Chưa hợp lắm");

  private final String label;

  Band(String label) {
    this.label = label;
  }

  public static Band of(double roundedTotal) {
    if (roundedTotal >= 8.0) {
      return RAT_HOP;
    }
    if (roundedTotal >= 6.5) {
      return KHA_HOP;
    }
    return roundedTotal >= 5.0 ? BINH_THUONG : CHUA_HOP;
  }

  /** {@code rat_hop}, {@code kha_hop}, {@code binh_thuong}, {@code chua_hop}. */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  public String label() {
    return label;
  }
}
