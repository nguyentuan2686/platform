package dev.platform.identity.application;

import java.util.UUID;

/** Moves what a guest owns onto the account the guest just logged into. */
public interface AccountMergePort {

  void mergeGuest(UUID guestId, UUID accountId);
}
