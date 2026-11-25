package dev.platform.reading.tuvi.domain.qa;

/** Why a question is not answered. The guard yields the first seven; the LLM sentinels the rest. */
public enum RefusalReason {
  MEDICAL,
  LEGAL,
  FINANCE,
  GAMBLING,
  DEATH,
  SELF_HARM,
  INJECTION,
  OFF_TOPIC,
  INSUFFICIENT
}
