package dev.platform.identity.application;

import java.util.UUID;

public interface EntitlementGrantPort {

  /** Grants the one-time signup bonus (1 credit) to a newly created account. */
  void signupBonus(UUID accountId);
}
