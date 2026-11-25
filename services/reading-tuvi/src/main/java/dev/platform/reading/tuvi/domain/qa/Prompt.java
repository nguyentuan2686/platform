package dev.platform.reading.tuvi.domain.qa;

import java.util.List;

/** Framework-free prompt; the application maps it to the gateway's message type. */
public record Prompt(List<Msg> messages, int maxTokens) {

  /** {@code role} is {@code system}, {@code user} or {@code assistant}. */
  public record Msg(String role, String content) {}
}
