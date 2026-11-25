package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.application.Birth;
import dev.platform.reading.tuvi.application.UpstreamUnavailableException;
import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.util.Optional;
import java.util.UUID;

/**
 * identity-svc: sessions, the chart profile and credits. Every method throws {@link
 * UpstreamUnavailableException} ({@code identity}) on a timeout, an I/O error or an unexpected
 * status.
 */
public interface IdentityPort {

  /**
   * @throws UnauthenticatedException when the session is unknown or expired
   */
  IdentitySubject whoAmI(String bearer);

  /** The caller's birth data; empty when there is no profile yet. */
  Optional<Birth> profile(String bearer);

  ConsumeOutcome consume(UUID accountId, UUID readingId, QaTopic topic);

  RefundOutcome refund(UUID readingId);
}
