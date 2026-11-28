package dev.platform.reading.tuvi.domain.number;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A normalized number to check (spec §5.1). {@code digits} is the sequence that is scored (for a
 * plate only its serial number); {@link #hashMaterial()} identifies the full normalized number for
 * the daily quota. {@link #toString()} never prints the number.
 */
public record NumberInput(NumberKind kind, String digits, String normalized) {

  private static final Pattern SIM_SEPARATORS = Pattern.compile("[\\s.\\-()]");
  private static final Pattern BANK_SEPARATORS = Pattern.compile("[\\s.\\-]");
  private static final Pattern SIM = Pattern.compile("^0\\d{9}$");
  private static final Pattern BANK = Pattern.compile("^\\d{6,19}$");
  private static final Pattern CAR = Pattern.compile("^\\d{2}[A-Z]{1,2}(\\d{4,5})$");
  private static final Pattern MOTORBIKE = Pattern.compile("^\\d{2}[A-Z]{1,2}\\d(\\d{4,5})$");
  private static final Pattern PLATE_JUNK = Pattern.compile("[^A-Z0-9]");
  private static final int MAX_RAW = 64;

  public NumberInput(NumberKind kind, String digits) {
    this(kind, digits, digits);
  }

  /**
   * @param vehicle required for {@link NumberKind#PLATE}, ignored otherwise
   * @throws InvalidNumberException {@code invalid_number} when the input does not fit its kind
   */
  public static NumberInput parse(NumberKind kind, Vehicle vehicle, String raw) {
    if (kind == null || raw == null || raw.isBlank() || raw.length() > MAX_RAW) {
      throw invalid();
    }
    return switch (kind) {
      case SIM -> sim(raw);
      case BANK -> bank(raw);
      case PLATE -> plate(vehicle, raw);
    };
  }

  private static NumberInput sim(String raw) {
    String s = SIM_SEPARATORS.matcher(raw).replaceAll("");
    if (s.startsWith("+84")) {
      s = "0" + s.substring(3);
    }
    if (!SIM.matcher(s).matches()) {
      throw invalid();
    }
    return new NumberInput(NumberKind.SIM, s, s);
  }

  private static NumberInput bank(String raw) {
    String s = BANK_SEPARATORS.matcher(raw).replaceAll("");
    if (!BANK.matcher(s).matches()) {
      throw invalid();
    }
    return new NumberInput(NumberKind.BANK, s, s);
  }

  private static NumberInput plate(Vehicle vehicle, String raw) {
    if (vehicle == null) {
      throw invalid();
    }
    String s = PLATE_JUNK.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    Matcher m = (vehicle == Vehicle.CAR ? CAR : MOTORBIKE).matcher(s);
    if (!m.matches()) {
      throw invalid();
    }
    return new NumberInput(NumberKind.PLATE, m.group(1), s);
  }

  private static InvalidNumberException invalid() {
    return new InvalidNumberException("invalid_number");
  }

  /** {@code <kind>|<full normalized number>}: the input of the quota HMAC. */
  public String hashMaterial() {
    return kind.key() + "|" + normalized;
  }

  @Override
  public String toString() {
    return "NumberInput[kind=" + kind + ", length=" + digits.length() + "]";
  }
}
