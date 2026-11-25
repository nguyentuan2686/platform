package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.SharedProfile;
import dev.platform.identity.domain.Calendar;

/**
 * Body of {@code GET /shares/{id}} (service-to-service). The web reduces it to the public fields;
 * {@code toString} is redacted.
 */
public record SharedProfileResponse(ProfileResponse.BirthResponse birth, Calendar calendar) {

  static SharedProfileResponse of(SharedProfile shared) {
    return new SharedProfileResponse(
        new ProfileResponse.BirthResponse(
            shared.birth().birth(), shared.birth().zone(), shared.birth().gender()),
        shared.calendar());
  }

  @Override
  public String toString() {
    return "SharedProfileResponse[redacted]";
  }
}
