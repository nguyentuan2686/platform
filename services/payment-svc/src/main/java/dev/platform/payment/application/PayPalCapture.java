package dev.platform.payment.application;

import dev.platform.payment.domain.Money;
import java.time.Instant;

/** A completed PayPal capture: its id, the PayPal order it paid, and the amount. */
public record PayPalCapture(
    String captureId, String paypalOrderId, Money amount, Instant occurredAt) {}
