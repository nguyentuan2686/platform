package dev.platform.identity.application;

import dev.platform.identity.domain.CreditSource;

public record ConsumeResult(CreditSource source, int creditsLeft) {}
