package dev.platform.reading.tuvi.domain.qa;

/** Allowed answer length (characters, after cleaning) per kind of answer. */
public enum AnswerLimits {
  PAID(150, 2000),
  FOLLOW_UP(60, 1200),
  FREE(60, 800);

  private final int min;
  private final int max;

  AnswerLimits(int min, int max) {
    this.min = min;
    this.max = max;
  }

  public int min() {
    return min;
  }

  public int max() {
    return max;
  }
}
