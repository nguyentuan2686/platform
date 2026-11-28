package dev.platform.reading.tuvi.application.number;

/**
 * {@code NUMBER_CHECK_DAILY_PER_SUBJECT} (2) distinct numbers per session subject and {@code
 * NUMBER_CHECK_DAILY_PER_IP} (20) per client IP, per Vietnam day.
 */
public record NumberCheckSettings(int perSubject, int perIp) {}
