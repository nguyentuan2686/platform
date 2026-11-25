package dev.platform.identity.application;

import dev.platform.identity.domain.CreditSource;
import java.time.Instant;

public record LedgerEntryView(
    int delta, CreditSource source, String reason, String topic, Instant createdAt) {}
