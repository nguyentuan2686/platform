package dev.platform.payment.application;

import java.util.UUID;

public interface IdentityCreditsPort {

  /**
   * Takes back the credits of a purchase; idempotent on {@code orderId}. Throws {@link
   * InsufficientCreditsException} when they were used and {@link UpstreamUnavailableException} when
   * identity-svc is down, slow or answers anything unexpected.
   */
  void reverse(UUID accountId, UUID orderId, int credits);
}
