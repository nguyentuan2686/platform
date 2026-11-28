package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.List;
import java.util.Optional;

/**
 * Chart-grounded context for one question: passage lookup chains, the palace facts, the yearly flow
 * fact, for timing questions the {@code THỜI VẬN} lines (calendar years only), and the current year
 * in Vietnam ({@code 0} = unknown: no {@code NĂM NAY} line).
 */
public record QaContext(
    List<List<KnowledgeKey>> lookupChains,
    List<PalaceFact> facts,
    Optional<String> yearlyFact,
    int currentYear,
    List<String> timing) {}
