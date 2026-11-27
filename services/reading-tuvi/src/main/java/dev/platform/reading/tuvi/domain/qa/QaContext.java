package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.List;
import java.util.Optional;

/**
 * Chart-grounded context for one question: passage lookup chains, the palace facts, the yearly flow
 * fact and, for timing questions, the {@code THỜI VẬN} lines (calendar years only).
 */
public record QaContext(
    List<List<KnowledgeKey>> lookupChains,
    List<PalaceFact> facts,
    Optional<String> yearlyFact,
    List<String> timing) {}
