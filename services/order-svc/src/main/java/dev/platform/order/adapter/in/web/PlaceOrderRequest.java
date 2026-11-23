package dev.platform.order.adapter.in.web;

import dev.platform.order.application.PlaceOrderCommand;
import dev.platform.order.domain.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record PlaceOrderRequest(
    @NotBlank String customerId, @NotEmpty List<@NotNull @Valid Item> items) {

  public record Item(
      @NotBlank String sku,
      @NotNull @Min(1) Integer quantity,
      @NotNull @DecimalMin("0") BigDecimal unitPrice) {}

  PlaceOrderCommand toCommand() {
    List<OrderLine> lines =
        items.stream().map(i -> new OrderLine(i.sku(), i.quantity(), i.unitPrice())).toList();
    return new PlaceOrderCommand(customerId, lines);
  }
}
