package dev.platform.reading.tuvi.domain.qa;

/** Outcome of {@link QuestionGuard#check(String)}. */
public sealed interface GuardResult {

  /** The question may be sent to the model; {@code sanitized} is delimiter- and PII-free. */
  record Ok(String sanitized) implements GuardResult {}

  /** Malformed input; {@code field} names the request field. */
  record Invalid(String field) implements GuardResult {}

  /** The question is declined without calling the model. */
  record Refused(RefusalReason reason) implements GuardResult {}
}
