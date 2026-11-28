package dev.platform.reading.tuvi.application.llm;

/**
 * One answer. {@code promptTokens} counts every input token, cache writes and reads included;
 * {@code cacheWriteTokens} and {@code cacheReadTokens} say how many of them were priced as such.
 */
public record LlmCompletion(
    String text,
    String provider,
    int promptTokens,
    int completionTokens,
    int cacheWriteTokens,
    int cacheReadTokens) {

  public LlmCompletion(String text, String provider, int promptTokens, int completionTokens) {
    this(text, provider, promptTokens, completionTokens, 0, 0);
  }
}
