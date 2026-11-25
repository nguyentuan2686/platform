package dev.platform.reading.tuvi.application.llm;

/** One LLM backend; throws LlmRateLimitedException or LlmUnavailableException. */
public interface LlmProviderPort {
  String id();

  boolean paid();

  int dailyRequestLimit();

  LlmCompletion complete(LlmRequest request);
}
