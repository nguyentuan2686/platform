package dev.platform.payment.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.payment.application.CreateOrderCommand;
import java.util.UUID;

/** Body of {@code POST /orders}. Presence and value rules live in the use case. */
public record CreateOrderRequest(
    @JsonProperty("account_id") UUID accountId,
    String site,
    @JsonProperty("product_code") String productCode,
    String currency,
    String provider,
    @JsonProperty("adult_confirmed") Boolean adultConfirmed,
    @JsonProperty("chart_profile_id") UUID chartProfileId) {

  CreateOrderCommand toCommand() {
    return new CreateOrderCommand(
        accountId, site, productCode, currency, provider, adultConfirmed, chartProfileId);
  }
}
