package dev.platform.reading.tuvi.application.qa;

import java.util.UUID;

/**
 * Hands a guest's Q&A history to the account the guest just logged into. One statement over the
 * guest's rows only; statuses, credit sources and refund state are untouched, so a reading that is
 * still owed an answer or a refund stays exactly as owed. A repeat moves nothing.
 */
public class QaMergeUseCase {

  private final QaRepositoryPort repo;

  public QaMergeUseCase(QaRepositoryPort repo) {
    this.repo = repo;
  }

  /** Returns the number of readings moved. */
  public int merge(UUID guestId, UUID accountId) {
    return repo.moveGuestReadings(guestId, accountId);
  }
}
