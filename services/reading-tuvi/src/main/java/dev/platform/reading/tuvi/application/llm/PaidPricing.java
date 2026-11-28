package dev.platform.reading.tuvi.application.llm;

import java.math.BigDecimal;

/** USD per million tokens and the monthly budget of one paid provider. */
public record PaidPricing(
    BigDecimal inputUsdPerMTok,
    BigDecimal outputUsdPerMTok,
    BigDecimal cacheWriteUsdPerMTok,
    BigDecimal cacheReadUsdPerMTok,
    BigDecimal monthlyBudgetUsd) {

  /** A provider without prompt caching: cache tokens (never reported) cost the input price. */
  public PaidPricing(
      BigDecimal inputUsdPerMTok, BigDecimal outputUsdPerMTok, BigDecimal monthlyBudgetUsd) {
    this(inputUsdPerMTok, outputUsdPerMTok, inputUsdPerMTok, inputUsdPerMTok, monthlyBudgetUsd);
  }
}
