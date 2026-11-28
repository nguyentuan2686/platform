package dev.platform.reading.tuvi.application.llm;

import java.util.Optional;

/**
 * The provider gave no usable answer. {@link #billed()} carries the usage of a reply the provider
 * still charges for (refused, truncated or text-less), so the gateway records its cost.
 */
public class LlmUnavailableException extends RuntimeException {
  private final transient LlmCompletion billed;

  public LlmUnavailableException(String message) {
    this(message, (LlmCompletion) null);
  }

  public LlmUnavailableException(String message, Throwable cause) {
    super(message, cause);
    this.billed = null;
  }

  public LlmUnavailableException(String message, LlmCompletion billed) {
    super(message);
    this.billed = billed;
  }

  public Optional<LlmCompletion> billed() {
    return Optional.ofNullable(billed);
  }
}
