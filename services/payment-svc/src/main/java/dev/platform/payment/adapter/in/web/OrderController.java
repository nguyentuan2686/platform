package dev.platform.payment.adapter.in.web;

import dev.platform.payment.application.CreateOrderUseCase;
import dev.platform.payment.application.CreatedOrder;
import dev.platform.payment.application.GetOrderUseCase;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service order API (guarded by X-Internal-Token); the web BFF supplies account_id. */
@RestController
public class OrderController {

  private final CreateOrderUseCase createOrder;
  private final GetOrderUseCase getOrder;

  public OrderController(CreateOrderUseCase createOrder, GetOrderUseCase getOrder) {
    this.createOrder = createOrder;
    this.getOrder = getOrder;
  }

  @PostMapping("/orders")
  ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest request) {
    CreatedOrder created = createOrder.create(request.toCommand());
    return ResponseEntity.created(URI.create("/orders/" + created.order().id()))
        .body(OrderResponse.from(created, created.order().status().name()));
  }

  @GetMapping("/orders/{id}")
  OrderResponse get(@PathVariable UUID id, @RequestParam("account_id") UUID accountId) {
    return OrderResponse.from(getOrder.get(id, accountId));
  }

  @GetMapping("/orders")
  List<OrderResponse> list(@RequestParam("account_id") UUID accountId) {
    return getOrder.list(accountId).stream().map(OrderResponse::from).toList();
  }
}
