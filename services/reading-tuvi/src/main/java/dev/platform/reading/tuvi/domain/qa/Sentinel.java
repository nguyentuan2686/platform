package dev.platform.reading.tuvi.domain.qa;

import java.util.Optional;

/** Markers the model answers with instead of prose when it must decline. */
public final class Sentinel {

  public static final String NGOAI_PHAM_VI = "NGOAI_PHAM_VI";
  public static final String KHONG_DU_TU_LIEU = "KHONG_DU_TU_LIEU";

  private Sentinel() {}

  /** The refusal the answer signals, when its trimmed text starts with a sentinel. */
  public static Optional<RefusalReason> detect(String answer) {
    if (answer == null) {
      return Optional.empty();
    }
    String s = answer.strip();
    if (s.startsWith(NGOAI_PHAM_VI)) {
      return Optional.of(RefusalReason.OFF_TOPIC);
    }
    if (s.startsWith(KHONG_DU_TU_LIEU)) {
      return Optional.of(RefusalReason.INSUFFICIENT);
    }
    return Optional.empty();
  }
}
