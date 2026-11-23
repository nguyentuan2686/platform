package dev.platform.order.adapter.in.web;

import dev.platform.order.application.GetOrderUseCase;
import dev.platform.order.application.PlaceOrderUseCase;
import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

  private final PlaceOrderUseCase placeOrder;
  private final GetOrderUseCase getOrder;

  public OrderController(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder) {
    this.placeOrder = placeOrder;
    this.getOrder = getOrder;
  }

  @PostMapping("/orders")
  ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
    Order order = placeOrder.place(request.toCommand());
    return ResponseEntity.created(URI.create("/orders/" + order.id()))
        .body(OrderResponse.from(order));
  }

  /** The id is bound as a UUID, so a malformed one is a type mismatch and maps to a 400 problem. */
  @GetMapping("/orders/{id}")
  OrderResponse get(@PathVariable UUID id) {
    return OrderResponse.from(getOrder.get(new OrderId(id)));
  }
}
