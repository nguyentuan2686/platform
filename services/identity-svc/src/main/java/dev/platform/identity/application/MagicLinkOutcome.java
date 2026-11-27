package dev.platform.identity.application;

/** What a magic-link request did; callers answer 202 either way and only count the refusals. */
public enum MagicLinkOutcome {
  SENT,
  /** 3 per address per 15 minutes. */
  ADDRESS_LIMITED,
  /** {@code MagicLinkPolicy.CLIENT_DAILY_MAX} per client IP per rolling day. */
  CLIENT_LIMITED
}
