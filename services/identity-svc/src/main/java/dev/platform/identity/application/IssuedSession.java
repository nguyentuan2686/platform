package dev.platform.identity.application;

import dev.platform.identity.domain.SessionToken;
import dev.platform.identity.domain.Subject;
import java.time.Instant;

public record IssuedSession(SessionToken token, Subject subject, Instant expiresAt) {}
