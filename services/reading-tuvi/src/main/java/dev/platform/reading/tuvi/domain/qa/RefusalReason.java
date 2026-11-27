package dev.platform.reading.tuvi.domain.qa;

/** Why a question is not answered. The guard yields the first eight; the LLM sentinels the rest. */
public enum RefusalReason {
  MEDICAL,
  LEGAL,
  FINANCE,
  GAMBLING,
  DEATH,
  SELF_HARM,
  INJECTION,
  /** Rites to change one's fate (giải hạn, cúng sao, bùa): Lão Thiên Cơ performs none. */
  RITUAL,
  OFF_TOPIC,
  INSUFFICIENT
}
