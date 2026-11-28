package dev.platform.reading.tuvi.domain.number;

/** What the checked number is: a phone SIM, a bank account number or a licence plate. */
public enum NumberKind {
  SIM,
  BANK,
  PLATE;

  /** Lowercase wire and knowledge form: {@code sim}, {@code bank}, {@code plate}. */
  public String key() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
