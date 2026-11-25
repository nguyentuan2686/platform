package dev.platform.identity.application;

import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;

/** What a share id resolves to, for the web to reduce to its public fields. */
public record SharedProfile(BirthData birth, Calendar calendar) {

  @Override
  public String toString() {
    return "SharedProfile[redacted]";
  }
}
