package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.LedgerEntryView;
import dev.platform.identity.domain.CreditSource;
import java.time.Instant;

public record LedgerEntryResponse(
    int delta,
    CreditSource source,
    String reason,
    String topic,
    @JsonProperty("created_at") Instant createdAt) {

  static LedgerEntryResponse of(LedgerEntryView v) {
    return new LedgerEntryResponse(v.delta(), v.source(), v.reason(), v.topic(), v.createdAt());
  }
}
