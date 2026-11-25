package dev.platform.identity.application;

import dev.platform.identity.domain.BirthData;
import java.util.UUID;

/** Seals birth data, bound to the profile it belongs to. */
public interface BirthCipherPort {

  /** Version of the key used by {@link #encrypt}, stored beside the ciphertext for rotation. */
  int currentKeyId();

  byte[] encrypt(UUID profileId, BirthData birth);

  /** Throws {@link ProfileCipherException} when the data was altered or belongs to another id. */
  BirthData decrypt(UUID profileId, byte[] sealed);
}
