package dev.platform.payment.support;

import dev.platform.payment.application.OrderRepositoryPort;
import dev.platform.payment.domain.Order;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory repository; {@code failInserts} simulates order-code clashes. */
public class InMemoryOrders implements OrderRepositoryPort {

  public final Map<UUID, Order> orders = new LinkedHashMap<>();
  public int failInserts;
  public int insertAttempts;

  @Override
  public boolean insert(Order order) {
    insertAttempts++;
    if (failInserts > 0) {
      failInserts--;
      return false;
    }
    orders.put(order.id(), order);
    return true;
  }

  @Override
  public void updateProviderRef(UUID id, String providerRef) {}

  @Override
  public Optional<Order> find(UUID id) {
    return Optional.ofNullable(orders.get(id));
  }

  @Override
  public Optional<Order> findForUpdate(UUID id) {
    return find(id);
  }

  @Override
  public List<Order> findByAccount(UUID accountId, int limit) {
    return new ArrayList<>(orders.values())
        .stream()
            .filter(o -> o.accountId().equals(accountId))
            .sorted(Comparator.comparing(Order::createdAt).reversed())
            .limit(limit)
            .toList();
  }

  @Override
  public void save(Order order) {
    orders.put(order.id(), order);
  }

  @Override
  public List<Order> findByCodes(Collection<String> codes) {
    return orders.values().stream().filter(o -> codes.contains(o.code().value())).toList();
  }

  @Override
  public Optional<Order> findByProviderRef(String providerRef) {
    return orders.values().stream().filter(o -> providerRef.equals(o.providerRef())).findFirst();
  }
}
