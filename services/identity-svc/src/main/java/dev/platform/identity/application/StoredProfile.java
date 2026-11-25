package dev.platform.identity.application;

import dev.platform.identity.domain.Calendar;
import java.util.UUID;

/** A profile as persisted: the birth data only as ciphertext. */
public record StoredProfile(UUID id, String label, byte[] birthEnc, int keyId, Calendar calendar) {

  @Override
  public String toString() {
    return "StoredProfile[redacted]";
  }
}
