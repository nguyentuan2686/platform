package dev.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChartProfileTest {

  static final BirthData BIRTH = new BirthData("1994-03-12T05:30", "Asia/Ho_Chi_Minh", "female");
  static final BirthData OTHER = new BirthData("1994-03-13T05:30", "Asia/Ho_Chi_Minh", "female");

  private static ChartProfile profile() {
    return ChartProfile.create(UUID.randomUUID(), "Me", BIRTH, Calendar.VN);
  }

  @Test
  void unlocked_birth_change_is_allowed() {
    ChartProfile updated = profile().update("Me", OTHER, Calendar.VN, false);

    assertThat(updated.birth()).isEqualTo(OTHER);
  }

  @Test
  void locked_birth_change_is_refused() {
    assertThatThrownBy(() -> profile().update("Me", OTHER, Calendar.VN, true))
        .isInstanceOf(ProfileLockedException.class);
  }

  @Test
  void locked_label_and_calendar_changes_are_allowed() {
    ChartProfile updated = profile().update("Mẹ", BIRTH, Calendar.CN, true);

    assertThat(updated.label()).isEqualTo("Mẹ");
    assertThat(updated.calendar()).isEqualTo(Calendar.CN);
  }

  @Test
  void locked_same_birth_is_allowed_even_if_written_differently() {
    BirthData sameInstant = new BirthData("1994-03-12T05:30:00", null, "female");

    ChartProfile updated = profile().update("Me", sameInstant, Calendar.VN, true);

    assertThat(updated.birth()).isEqualTo(BIRTH);
  }

  @Test
  void update_keeps_the_id() {
    ChartProfile before = profile();

    assertThat(before.update("X", OTHER, Calendar.CN, false).id()).isEqualTo(before.id());
  }

  @Test
  void label_must_be_1_to_40_characters() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> ChartProfile.create(id, "", BIRTH, Calendar.VN))
        .isInstanceOf(ProfileValidationException.class)
        .extracting("field")
        .isEqualTo("label");
    assertThatThrownBy(() -> ChartProfile.create(id, "x".repeat(41), BIRTH, Calendar.VN))
        .isInstanceOf(ProfileValidationException.class);
    assertThat(ChartProfile.create(id, "x".repeat(40), BIRTH, Calendar.VN).label()).hasSize(40);
  }

  @Test
  void birth_data_is_validated_and_defaults_the_zone() {
    assertThat(new BirthData("1994-03-12T05:30", null, "male").zone())
        .isEqualTo("Asia/Ho_Chi_Minh");
    assertThatThrownBy(() -> new BirthData("1994-03-12", "Asia/Ho_Chi_Minh", "male"))
        .isInstanceOf(ProfileValidationException.class)
        .extracting("field")
        .isEqualTo("birth.birth");
    assertThatThrownBy(() -> new BirthData("1994-02-30T05:30", "Asia/Ho_Chi_Minh", "male"))
        .isInstanceOf(ProfileValidationException.class);
    assertThatThrownBy(() -> new BirthData("1994-03-12T05:30", "Mars/Olympus", "male"))
        .isInstanceOf(ProfileValidationException.class)
        .extracting("field")
        .isEqualTo("birth.zone");
    assertThatThrownBy(() -> new BirthData("1994-03-12T05:30", "Asia/Ho_Chi_Minh", "other"))
        .isInstanceOf(ProfileValidationException.class)
        .extracting("field")
        .isEqualTo("birth.gender");
  }

  @Test
  void to_string_never_shows_birth_data() {
    assertThat(BIRTH.toString()).doesNotContain("1994").doesNotContain("female");
    assertThat(profile().toString()).doesNotContain("1994").doesNotContain("Me");
  }
}
