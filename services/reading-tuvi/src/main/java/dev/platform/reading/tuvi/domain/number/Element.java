package dev.platform.reading.tuvi.domain.number;

/** The five elements with the generating (sinh) and controlling (khắc) cycles. */
public enum Element {
  KIM("kim", "Kim"),
  MOC("moc", "Mộc"),
  THUY("thuy", "Thủy"),
  HOA("hoa", "Hỏa"),
  THO("tho", "Thổ");

  private final String key;
  private final String label;

  Element(String key, String label) {
    this.key = key;
    this.label = label;
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }

  /** Kim → Thủy → Mộc → Hỏa → Thổ → Kim. */
  public Element generates() {
    return switch (this) {
      case KIM -> THUY;
      case THUY -> MOC;
      case MOC -> HOA;
      case HOA -> THO;
      case THO -> KIM;
    };
  }

  /** Kim → Mộc → Thổ → Thủy → Hỏa → Kim. */
  public Element controls() {
    return switch (this) {
      case KIM -> MOC;
      case MOC -> THO;
      case THO -> THUY;
      case THUY -> HOA;
      case HOA -> KIM;
    };
  }
}
