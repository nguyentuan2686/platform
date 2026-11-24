package dev.platform.reading.tuvi.domain;

/** A star in a palace; {@code brightness} and {@code mutagen} are null when absent. */
public record StarPlacement(String key, String nameVi, String brightness, String mutagen) {}
