package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReversalRequest(
    @JsonProperty("account_id") @NotNull UUID accountId,
    @JsonProperty("order_id") @NotNull UUID orderId,
    @Min(1) int credits) {}
