package dev.platform.reading.tuvi.domain.qa;

import java.util.Optional;
import java.util.regex.Pattern;

/** Markers the model answers with instead of prose when it must decline. */
public final class Sentinel {

  public static final String NGOAI_PHAM_VI = "NGOAI_PHAM_VI";
  public static final String KHONG_DU_TU_LIEU = "KHONG_DU_TU_LIEU";

  private static final Pattern DECORATION = Pattern.compile("^[\\s*_\"'`]+");

  private Sentinel() {}

  /** The refusal the answer signals, when its trimmed text starts with a sentinel. */
  public static Optional<RefusalReason> detect(String answer) {
    if (answer == null) {
      return Optional.empty();
    }
    // tolerate markdown or quote decoration: **NGOAI_PHAM_VI**, "`KHONG_DU_TU_LIEU`"
    String s = DECORATION.matcher(answer).replaceFirst("");
    // models misspell the tail (KHONG_DU_TIEU_LIEU); the underscore-joined head is unambiguous
    if (s.startsWith(NGOAI_PHAM_VI) || s.startsWith("NGOAI_PHAM_")) {
      return Optional.of(RefusalReason.OFF_TOPIC);
    }
    if (s.startsWith(KHONG_DU_TU_LIEU) || s.startsWith("KHONG_DU_")) {
      return Optional.of(RefusalReason.INSUFFICIENT);
    }
    return Optional.empty();
  }
}
