package dev.platform.reading.tuvi.application.qa;

import java.util.Optional;

/** Encrypted first answers, keyed by a SHA-256 of the chart, topic, kind and question. */
public interface QaAnswerCachePort {

  Optional<byte[]> find(String cacheKey);

  /** Keeps the first entry for a key. */
  void put(String cacheKey, byte[] answerEnc);
}
