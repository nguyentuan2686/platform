package dev.platform.reading.tuvi.application.qa;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes free chats (and answer-cache entries) older than {@code QA_FREE_RETENTION}. Paid readings
 * are the purchased product and stay until the user deletes the history; a reading still owed an
 * answer or a refund is never deleted.
 */
public class QaRetentionUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(QaRetentionUseCase.class);

  private final QaRepositoryPort repo;
  private final Duration retention;

  public QaRetentionUseCase(QaRepositoryPort repo, Duration retention) {
    this.repo = repo;
    this.retention = retention;
  }

  public void purge(Instant now) {
    Instant before = now.minus(retention);
    int readings = repo.purgeFree(before);
    int cached = repo.purgeCache(before);
    LOG.info("qa retention done: free_readings={} cache_entries={}", readings, cached);
  }
}
