package dev.platform.reading.tuvi.domain.qa;

/** Which answer is being asked for; sets length guidance and the token budget. */
public enum PromptKind {
  PAID(900, "120 đến 250 từ"),
  FOLLOW_UP(600, "tối đa 150 từ"),
  FREE(350, "tối đa 80 từ");

  private final int maxTokens;
  private final String lengthVi;

  PromptKind(int maxTokens, String lengthVi) {
    this.maxTokens = maxTokens;
    this.lengthVi = lengthVi;
  }

  public int maxTokens() {
    return maxTokens;
  }

  String lengthVi() {
    return lengthVi;
  }
}
