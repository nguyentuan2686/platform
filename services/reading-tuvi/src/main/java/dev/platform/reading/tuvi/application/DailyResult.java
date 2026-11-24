package dev.platform.reading.tuvi.application;

import dev.platform.reading.tuvi.domain.DailyFacts;

/** Flow facts of a day plus the display forms of its lunar date and day can chi. */
public record DailyResult(DailyFacts facts, String lunar, String dayCanChi) {}
