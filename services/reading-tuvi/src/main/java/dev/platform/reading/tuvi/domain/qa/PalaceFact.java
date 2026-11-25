package dev.platform.reading.tuvi.domain.qa;

import java.util.List;

/** Vietnamese facts of one palace as sent to the model (no ids, no birth data). */
public record PalaceFact(String palaceNameVi, List<String> stars, boolean tuan, boolean triet) {}
