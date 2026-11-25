package dev.platform.reading.tuvi.application.llm;

/** No provider could answer. */
public class AllProvidersExhaustedException extends RuntimeException {
  public AllProvidersExhaustedException() {
    super("no llm provider available");
  }
}
