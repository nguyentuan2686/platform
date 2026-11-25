package dev.platform.reading.tuvi.domain.qa;

/** An earlier question/answer of the same reading (question already sanitized). */
public record Turn(String question, String answer) {}
