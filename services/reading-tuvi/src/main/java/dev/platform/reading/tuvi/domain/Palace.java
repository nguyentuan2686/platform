package dev.platform.reading.tuvi.domain;

import java.util.List;

/** One of the twelve palaces; {@code key} is the stable ASCII palace key, e.g. {@code Menh}. */
public record Palace(
    String key,
    String nameVi,
    String branch,
    boolean isBody,
    List<StarPlacement> major,
    List<StarPlacement> minor,
    boolean tuan,
    boolean triet) {}
