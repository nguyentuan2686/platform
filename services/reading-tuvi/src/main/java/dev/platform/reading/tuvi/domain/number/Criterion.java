package dev.platform.reading.tuvi.domain.number;

/**
 * One scored criterion. {@code key} is {@code element|hexagram|yinyang|cungphi|nut|pairs}; {@code
 * detail} names the outcome (never the whole number); {@code readingKey} is the knowledge key of
 * the outcome, null for {@code pairs} (those come as a list on {@link NumberScore}).
 */
public record Criterion(
    String key, String label, String detail, double points, double max, String readingKey) {}
