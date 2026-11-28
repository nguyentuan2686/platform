package dev.platform.reading.tuvi.application.llm;

/** One LLM backend; throws LlmRateLimitedException or LlmUnavailableException. */
public interface LlmProviderPort {
  String id();

  /** Costs money: priced and held to its monthly budget. */
  boolean paid();

  int dailyRequestLimit();

  /** May answer {@link LlmTier#FREE_ONLY} requests; by default only free providers do. */
  default boolean servesFreeTier() {
    return !paid();
  }

  LlmCompletion complete(LlmRequest request);
}
