package dev.platform.reading.tuvi.adapter.out.llm;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for one OpenAI-compatible provider. {@code reasoningEffort} is sent as {@code
 * reasoning_effort} when not blank (reasoning models such as Groq's gpt-oss spend {@code
 * max_tokens} on reasoning too). {@code servesFreeTier} lets a cheap paid model answer free
 * questions within its budget. {@link #toString} redacts the key.
 */
public record OpenAiCompatibleSettings(
    String id,
    URI baseUrl,
    String apiKey,
    String model,
    int dailyRequestLimit,
    boolean paid,
    Duration timeout,
    String reasoningEffort,
    boolean servesFreeTier) {

  /** Only a free provider serves the free tier. */
  public OpenAiCompatibleSettings(
      String id,
      URI baseUrl,
      String apiKey,
      String model,
      int dailyRequestLimit,
      boolean paid,
      Duration timeout,
      String reasoningEffort) {
    this(id, baseUrl, apiKey, model, dailyRequestLimit, paid, timeout, reasoningEffort, !paid);
  }

  public OpenAiCompatibleSettings(
      String id,
      URI baseUrl,
      String apiKey,
      String model,
      int dailyRequestLimit,
      boolean paid,
      Duration timeout) {
    this(id, baseUrl, apiKey, model, dailyRequestLimit, paid, timeout, "");
  }

  @Override
  public String toString() {
    return "OpenAiCompatibleSettings[id="
        + id
        + ", baseUrl="
        + baseUrl
        + ", apiKey=***, model="
        + model
        + ", dailyRequestLimit="
        + dailyRequestLimit
        + ", paid="
        + paid
        + ", timeout="
        + timeout
        + ", reasoningEffort="
        + reasoningEffort
        + ", servesFreeTier="
        + servesFreeTier
        + "]";
  }
}
