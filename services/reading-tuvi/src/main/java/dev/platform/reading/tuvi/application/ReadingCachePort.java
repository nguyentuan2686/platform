package dev.platform.reading.tuvi.application;

import java.util.Optional;

/** Cache of rendered basic readings, keyed by chart hash, knowledge version and render version. */
public interface ReadingCachePort {

  Optional<BasicReading> find(String chartHash, long knowledgeVersion, int renderVersion);

  /** Stores the reading under its own chart hash and knowledge version (idempotent). */
  void put(BasicReading reading, int renderVersion);
}
