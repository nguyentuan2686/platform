package dev.platform.reading.tuvi.application.qa;

/** Result of spending a credit for a reading. */
public sealed interface ConsumeOutcome {

  /** Spent; {@code source} is identity-svc {@code CREDIT} or {@code PASS}. */
  record Charged(String source) implements ConsumeOutcome {}

  /** The 409 {@code reason} of identity-svc, verbatim ({@code no_credits}, {@code daily_cap}). */
  record Refused(String reason) implements ConsumeOutcome {}
}
