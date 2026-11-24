package dev.platform.reading.tuvi.application;

import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.Optional;

/** Read side of the knowledge base; serves approved content only. */
public interface KnowledgePort {

  /** Approved content for the key in the given language, if any. */
  Optional<String> find(KnowledgeKey key, String lang);

  /** Monotonic knowledge version; bumps once per import that changed anything. */
  long version();
}
