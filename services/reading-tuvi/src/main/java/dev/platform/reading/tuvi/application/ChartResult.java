package dev.platform.reading.tuvi.application;

import dev.platform.reading.tuvi.domain.Chart;

/** A natal chart with the hash of chart-svc's response body and its {@code school} string. */
public record ChartResult(Chart chart, String chartHash, String school) {}
