package dev.platform.reading.tuvi.application;

import java.util.List;

/** A rendered basic reading; {@code title} of a section is null when it has none. */
public record BasicReading(
    String character,
    String school,
    String chartHash,
    long knowledgeVersion,
    List<RenderedSection> sections) {

  public record RenderedSection(String id, String title, List<String> lines) {}
}
