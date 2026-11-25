package dev.platform.identity.domain;

import java.util.UUID;

/** A person's chart profile: one per subject. {@code toString} is redacted. */
public record ChartProfile(UUID id, String label, BirthData birth, Calendar calendar) {

  public static final int MAX_LABEL = 40;

  public ChartProfile {
    if (label == null || label.isEmpty() || label.length() > MAX_LABEL) {
      throw new ProfileValidationException("label", "label must be 1 to 40 characters");
    }
    if (id == null || birth == null || calendar == null) {
      throw new IllegalArgumentException("incomplete profile");
    }
  }

  public static ChartProfile create(UUID id, String label, BirthData birth, Calendar calendar) {
    return new ChartProfile(id, label, birth, calendar);
  }

  /** Label and calendar are always editable; the birth only while it is not locked. */
  public ChartProfile update(
      String label, BirthData birth, Calendar calendar, boolean birthLocked) {
    if (birthLocked && !this.birth.equals(birth)) {
      throw new ProfileLockedException();
    }
    return new ChartProfile(id, label, birth, calendar);
  }

  @Override
  public String toString() {
    return "ChartProfile[redacted]";
  }
}
