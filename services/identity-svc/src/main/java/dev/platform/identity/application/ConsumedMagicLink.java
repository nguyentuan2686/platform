package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import java.util.UUID;

/** What a successfully redeemed magic link carries. {@code guestId} may be null. */
public record ConsumedMagicLink(Email email, UUID guestId) {}
