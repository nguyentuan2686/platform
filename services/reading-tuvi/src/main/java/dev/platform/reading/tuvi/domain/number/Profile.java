package dev.platform.reading.tuvi.domain.number;

/**
 * The person's side of a number check: lunar year (after the Tết adjustment), can chi, nạp âm and
 * its element, and the cung phi with its East/West group.
 */
public record Profile(
    int lunarYear,
    String canChi,
    String napAm,
    Element element,
    int cungPhi,
    String cungPhiName,
    boolean eastGroup) {

  public static final int MIN_YEAR = 1930;

  /**
   * @param solarYear the Gregorian birth year
   * @param beforeTet born before Tết Nguyên đán of that year (lunar year = solar year - 1)
   * @throws InvalidNumberException {@code invalid_birth} when the year is outside 1930..current
   *     year or the gender is missing
   */
  public static Profile of(int solarYear, boolean beforeTet, Gender gender, int currentYear) {
    if (solarYear < MIN_YEAR || solarYear > currentYear || gender == null) {
      throw new InvalidNumberException("invalid_birth");
    }
    int y = beforeTet ? solarYear - 1 : solarYear;
    int cung = CungPhi.of(y, gender);
    return new Profile(
        y,
        NapAm.canChi(y),
        NapAm.name(y),
        NapAm.element(y),
        cung,
        CungPhi.name(cung),
        CungPhi.isEast(cung));
  }

  /** Redacted: the lunar year and can chi reveal the birth year. */
  @Override
  public String toString() {
    return "Profile[redacted]";
  }
}
