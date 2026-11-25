package dev.platform.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.identity.application.ProfileCipherException;
import dev.platform.identity.domain.BirthData;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmBirthCipherAdapterTest {

  // Test-only key: 32 bytes of a fixed phrase.
  static final String KEY =
      Base64.getEncoder()
          .encodeToString("local-test-profile-enc-key-00000".getBytes(StandardCharsets.UTF_8));
  static final BirthData BIRTH = new BirthData("1994-03-12T05:30", "Asia/Ho_Chi_Minh", "female");

  AesGcmBirthCipherAdapter cipher = new AesGcmBirthCipherAdapter(KEY, new SecureRandom());

  @Test
  void round_trips() {
    UUID id = UUID.randomUUID();

    assertThat(cipher.decrypt(id, cipher.encrypt(id, BIRTH))).isEqualTo(BIRTH);
  }

  @Test
  void two_encryptions_of_the_same_birth_differ() {
    UUID id = UUID.randomUUID();

    assertThat(cipher.encrypt(id, BIRTH)).isNotEqualTo(cipher.encrypt(id, BIRTH));
  }

  @Test
  void ciphertext_of_one_profile_does_not_decrypt_as_another() {
    byte[] sealed = cipher.encrypt(UUID.randomUUID(), BIRTH);

    assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), sealed))
        .isInstanceOf(ProfileCipherException.class);
  }

  @Test
  void tampered_ciphertext_is_rejected() {
    UUID id = UUID.randomUUID();
    byte[] sealed = cipher.encrypt(id, BIRTH);
    sealed[sealed.length - 1] ^= 1;

    assertThatThrownBy(() -> cipher.decrypt(id, sealed)).isInstanceOf(ProfileCipherException.class);
  }

  @Test
  void ciphertext_does_not_contain_the_birth_string() {
    byte[] sealed = cipher.encrypt(UUID.randomUUID(), BIRTH);

    assertThat(new String(sealed, StandardCharsets.ISO_8859_1))
        .doesNotContain("1994-03-12")
        .doesNotContain("female");
  }

  @Test
  void key_id_is_1() {
    assertThat(cipher.currentKeyId()).isEqualTo(1);
  }

  @Test
  void key_must_be_32_base64_bytes() {
    String short16 = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new AesGcmBirthCipherAdapter(short16, new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AesGcmBirthCipherAdapter("", new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AesGcmBirthCipherAdapter("not base64!!", new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AesGcmBirthCipherAdapter(null, new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
  }
}
