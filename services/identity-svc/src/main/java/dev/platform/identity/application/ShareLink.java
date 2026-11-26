package dev.platform.identity.application;

import dev.platform.identity.domain.ShareId;
import java.time.Instant;

/** An active public share link of a chart profile. */
public record ShareLink(ShareId id, Instant createdAt) {}
