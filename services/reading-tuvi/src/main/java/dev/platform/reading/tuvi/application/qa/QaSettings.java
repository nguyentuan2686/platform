package dev.platform.reading.tuvi.application.qa;

import java.time.Duration;

/**
 * {@code QA_FREE_DAILY_PER_SUBJECT} (0 disables free chat), {@code QA_FREE_GLOBAL_DAILY_CAP} and
 * {@code QA_RETRY_WINDOW}.
 */
public record QaSettings(int freeDailyPerSubject, int freeGlobalDailyCap, Duration retryWindow) {}
