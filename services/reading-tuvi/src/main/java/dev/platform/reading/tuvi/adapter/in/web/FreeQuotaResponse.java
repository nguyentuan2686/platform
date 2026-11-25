package dev.platform.reading.tuvi.adapter.in.web;

import dev.platform.reading.tuvi.application.qa.FreeQuota;

/** Body of {@code GET /tuvi/qa/free-quota}. */
public record FreeQuotaResponse(int limit, int used, int remaining) {

  static FreeQuotaResponse from(FreeQuota q) {
    return new FreeQuotaResponse(q.limit(), q.used(), q.remaining());
  }
}
