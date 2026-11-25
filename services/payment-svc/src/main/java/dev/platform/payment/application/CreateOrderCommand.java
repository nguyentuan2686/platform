package dev.platform.payment.application;

import java.util.UUID;

/** Raw request values; the use case validates them and reports the first bad field. */
public record CreateOrderCommand(
    UUID accountId,
    String site,
    String productCode,
    String currency,
    String provider,
    Boolean adultConfirmed,
    UUID chartProfileId) {}
