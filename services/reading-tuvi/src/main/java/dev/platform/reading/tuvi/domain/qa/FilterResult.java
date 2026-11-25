package dev.platform.reading.tuvi.domain.qa;

/** Outcome of {@link AnswerFilter#check(String, AnswerLimits)}. */
public sealed interface FilterResult {

  /** The answer may be shown; {@code cleaned} has markdown and citation markers removed. */
  record Pass(String cleaned) implements FilterResult {}

  /** The answer breaks {@code rule}; the text itself is never carried. */
  record Reject(String rule) implements FilterResult {}
}
