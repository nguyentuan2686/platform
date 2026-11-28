package dev.platform.reading.tuvi.adapter.out.llm;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for the Anthropic Messages API. {@code thinkingHeadroomTokens} is added to
 * each request's answer budget because adaptive thinking counts toward {@code max_tokens}. {@link
 * #toString} redacts the key.
 */
public record AnthropicSettings(
    String id,
    URI baseUrl,
    String apiKey,
    String model,
    String effort,
    int thinkingHeadroomTokens,
    boolean promptCache,
    int dailyRequestLimit,
    boolean servesFreeTier,
    Duration timeout) {

  @Override
  public String toString() {
    return "AnthropicSettings[id="
        + id
        + ", baseUrl="
        + baseUrl
        + ", apiKey=***, model="
        + model
        + ", effort="
        + effort
        + ", thinkingHeadroomTokens="
        + thinkingHeadroomTokens
        + ", promptCache="
        + promptCache
        + ", dailyRequestLimit="
        + dailyRequestLimit
        + ", servesFreeTier="
        + servesFreeTier
        + ", timeout="
        + timeout
        + "]";
  }
}
