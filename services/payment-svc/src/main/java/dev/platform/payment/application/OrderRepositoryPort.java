package dev.platform.payment.application;

import dev.platform.payment.domain.Order;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepositoryPort {

  /** Inserts the order; false (nothing written) when its code is already taken. */
  boolean insert(Order order);

  void updateProviderRef(UUID id, String providerRef);

  Optional<Order> find(UUID id);

  /** Locks the row for the caller's transaction. */
  Optional<Order> findForUpdate(UUID id);

  /** Orders whose code is one of {@code codes}. */
  List<Order> findByCodes(Collection<String> codes);

  /** The order carrying this provider reference (the PayPal order id). */
  Optional<Order> findByProviderRef(String providerRef);

  /** Newest first. */
  List<Order> findByAccount(UUID accountId, int limit);

  /** Persists the mutable state: status, provider reference, note, paid time. */
  void save(Order order);
}
