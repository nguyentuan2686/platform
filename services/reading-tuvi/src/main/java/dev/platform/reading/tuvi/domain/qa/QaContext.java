package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.List;
import java.util.Optional;

/** Chart-grounded context for one question: passage lookup chains plus the palace facts. */
public record QaContext(
    List<List<KnowledgeKey>> lookupChains, List<PalaceFact> facts, Optional<String> yearlyFact) {}
