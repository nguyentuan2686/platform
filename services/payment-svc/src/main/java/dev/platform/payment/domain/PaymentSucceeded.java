package dev.platform.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A paid order: the integration event identity-svc turns into credits or a pass. */
public record PaymentSucceeded(
    UUID orderId,
    String orderCode,
    UUID accountId,
    String productCode,
    String site,
    Money amount,
    Provider provider,
    Instant paidAt,
    UUID chartProfileId) {}
