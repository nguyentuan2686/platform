package dev.platform.payment.support;

import dev.platform.payment.application.RefundRequestPort;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryRefundRequests implements RefundRequestPort {

  public final Map<UUID, RefundRequest> requests = new LinkedHashMap<>();

  @Override
  public Optional<RefundRequest> findByOrder(UUID orderId) {
    return requests.values().stream().filter(r -> r.orderId().equals(orderId)).findFirst();
  }

  @Override
  public Optional<RefundRequest> findForUpdate(UUID id) {
    return Optional.ofNullable(requests.get(id));
  }

  @Override
  public boolean insert(RefundRequest request) {
    if (findByOrder(request.orderId()).isPresent()) {
      return false;
    }
    requests.put(request.id(), request);
    return true;
  }

  @Override
  public void save(RefundRequest request) {
    requests.put(request.id(), request);
  }

  @Override
  public List<RefundRequest> list(RefundStatus status) {
    return requests.values().stream().filter(r -> r.status() == status).toList();
  }
}
