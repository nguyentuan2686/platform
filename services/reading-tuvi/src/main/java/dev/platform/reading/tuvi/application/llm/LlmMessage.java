package dev.platform.reading.tuvi.application.llm;

/** One chat message. */
public record LlmMessage(Role role, String content) {
  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT
  }
}
