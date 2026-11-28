package dev.platform.reading.tuvi.domain.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NumberInputTest {

  private static void invalid(NumberKind kind, Vehicle vehicle, String raw) {
    assertThatThrownBy(() -> NumberInput.parse(kind, vehicle, raw))
        .isInstanceOf(InvalidNumberException.class)
        .extracting(e -> ((InvalidNumberException) e).reason())
        .isEqualTo("invalid_number");
  }

  @Test
  void sim_accepts_spaces_dots_dashes_and_plus84() {
    assertThat(NumberInput.parse(NumberKind.SIM, null, "+84 912 345 678").digits())
        .isEqualTo("0912345678");
    assertThat(NumberInput.parse(NumberKind.SIM, null, "0912.345-678").digits())
        .isEqualTo("0912345678");
    assertThat(NumberInput.parse(NumberKind.SIM, null, "0912345678").kind())
        .isEqualTo(NumberKind.SIM);
  }

  @Test
  void sim_rejects_wrong_length_letters_and_missing_leading_zero() {
    invalid(NumberKind.SIM, null, "091234567");
    invalid(NumberKind.SIM, null, "09123456789");
    invalid(NumberKind.SIM, null, "1912345678");
    invalid(NumberKind.SIM, null, "09123a5678");
    invalid(NumberKind.SIM, null, null);
    invalid(NumberKind.SIM, null, "");
    invalid(null, null, "0912345678");
  }

  @Test
  void bank_accepts_6_to_19_digits() {
    assertThat(NumberInput.parse(NumberKind.BANK, null, "0123 4567 89").digits())
        .isEqualTo("0123456789");
    assertThat(NumberInput.parse(NumberKind.BANK, null, "123456").digits()).isEqualTo("123456");
    assertThat(NumberInput.parse(NumberKind.BANK, null, "1234567890123456789").digits())
        .hasSize(19);
    invalid(NumberKind.BANK, null, "12345");
    invalid(NumberKind.BANK, null, "12345678901234567890");
    invalid(NumberKind.BANK, null, "12345x78");
  }

  @Test
  void car_plate_scores_the_serial_number_only() {
    assertThat(NumberInput.parse(NumberKind.PLATE, Vehicle.CAR, "30a-123.45").digits())
        .isEqualTo("12345");
    assertThat(NumberInput.parse(NumberKind.PLATE, Vehicle.CAR, "29LD-1234").digits())
        .isEqualTo("1234");
    invalid(NumberKind.PLATE, Vehicle.CAR, "30A123");
    invalid(NumberKind.PLATE, Vehicle.CAR, "3A12345");
  }

  @Test
  void motorbike_plate_skips_the_series_digit() {
    assertThat(NumberInput.parse(NumberKind.PLATE, Vehicle.MOTORBIKE, "29-B1 123.45").digits())
        .isEqualTo("12345");
    assertThat(NumberInput.parse(NumberKind.PLATE, Vehicle.MOTORBIKE, "59X3-1234").digits())
        .isEqualTo("1234");
    invalid(NumberKind.PLATE, Vehicle.MOTORBIKE, "59X1234");
  }

  @Test
  void plate_without_vehicle_is_invalid() {
    invalid(NumberKind.PLATE, null, "30A12345");
  }

  @Test
  void hash_material_is_the_same_for_two_spellings_of_one_number() {
    String a = NumberInput.parse(NumberKind.SIM, null, "0912 345 678").hashMaterial();
    String b = NumberInput.parse(NumberKind.SIM, null, "+84912345678").hashMaterial();
    assertThat(a).isEqualTo(b).isEqualTo("sim|0912345678");
    assertThat(NumberInput.parse(NumberKind.PLATE, Vehicle.CAR, "30a-123.45").hashMaterial())
        .isEqualTo("plate|30A12345");
  }

  @Test
  void the_exception_message_and_to_string_never_carry_the_number() {
    assertThatThrownBy(() -> NumberInput.parse(NumberKind.SIM, null, "091234567"))
        .hasMessageNotContaining("091234567");
    assertThat(NumberInput.parse(NumberKind.SIM, null, "0912345678").toString())
        .doesNotContain("912345678");
  }
}
