package dev.platform.payment.application;

import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestPort {

  Optional<RefundRequest> findByOrder(UUID orderId);

  Optional<RefundRequest> findForUpdate(UUID id);

  /** False (nothing written) when the order already has a request. */
  boolean insert(RefundRequest request);

  void save(RefundRequest request);

  /** Oldest first. */
  List<RefundRequest> list(RefundStatus status);
}
