package dev.platform.identity.adapter.in.web;

import dev.platform.identity.domain.Calendar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /me/profile}. Field rules beyond presence live in the domain. */
public record ProfileRequest(
    @NotNull String label, @NotNull @Valid BirthRequest birth, @NotNull Calendar calendar) {

  /** Birth data as sent; {@code toString} is redacted. */
  public record BirthRequest(@NotNull String birth, String zone, @NotNull String gender) {

    @Override
    public String toString() {
      return "BirthRequest[redacted]";
    }
  }

  @Override
  public String toString() {
    return "ProfileRequest[redacted]";
  }
}
