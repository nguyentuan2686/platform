package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.ProfileView;
import dev.platform.identity.domain.BirthData;
import dev.platform.identity.domain.Calendar;
import dev.platform.identity.domain.ChartProfile;
import java.util.UUID;

/** Body of {@code GET/PUT /me/profile}. {@code toString} is redacted. */
public record ProfileResponse(
    UUID id, String label, BirthResponse birth, Calendar calendar, boolean locked) {

  public record BirthResponse(String birth, String zone, String gender) {

    @Override
    public String toString() {
      return "BirthResponse[redacted]";
    }
  }

  static ProfileResponse of(ProfileView view) {
    ChartProfile p = view.profile();
    BirthData b = p.birth();
    return new ProfileResponse(
        p.id(),
        p.label(),
        new BirthResponse(b.birth(), b.zone(), b.gender()),
        p.calendar(),
        view.locked());
  }

  @Override
  public String toString() {
    return "ProfileResponse[redacted]";
  }
}
