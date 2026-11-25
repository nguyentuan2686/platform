package dev.platform.payment.application;

import java.net.URI;

public record PayPalCheckout(String paypalOrderId, URI approveUrl) {}
