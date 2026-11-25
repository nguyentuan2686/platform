package dev.platform.reading.tuvi.application.llm;

import java.math.BigDecimal;

public record PaidPricing(
    BigDecimal inputUsdPerMTok, BigDecimal outputUsdPerMTok, BigDecimal monthlyBudgetUsd) {}
