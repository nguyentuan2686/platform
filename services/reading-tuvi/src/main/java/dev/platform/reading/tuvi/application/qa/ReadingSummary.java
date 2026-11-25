package dev.platform.reading.tuvi.application.qa;

import dev.platform.reading.tuvi.domain.qa.QaTopic;
import java.time.Instant;
import java.util.UUID;

/** One line of the caller's Q&A history. */
public record ReadingSummary(
    UUID readingId,
    QaKind kind,
    QaTopic topic,
    QaStatus status,
    Instant createdAt,
    int followUpsLeft) {}
