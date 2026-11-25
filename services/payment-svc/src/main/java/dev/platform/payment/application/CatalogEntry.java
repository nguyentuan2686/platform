package dev.platform.payment.application;

import dev.platform.payment.domain.Money;

public record CatalogEntry(String code, Money price) {}
