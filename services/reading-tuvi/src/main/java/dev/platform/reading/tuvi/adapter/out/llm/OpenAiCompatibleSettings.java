package dev.platform.reading.tuvi.adapter.out.llm;

import java.net.URI;
import java.time.Duration;

/** Connection settings for one OpenAI-compatible provider. {@link #toString} redacts the key. */
public record OpenAiCompatibleSettings(
    String id,
    URI baseUrl,
    String apiKey,
    String model,
    int dailyRequestLimit,
    boolean paid,
    Duration timeout) {

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
        + "]";
  }
}
