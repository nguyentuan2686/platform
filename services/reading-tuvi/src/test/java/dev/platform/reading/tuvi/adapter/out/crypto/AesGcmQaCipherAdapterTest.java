package dev.platform.reading.tuvi.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmQaCipherAdapterTest {

  // Test-only key: base64 of a fixed 32-byte test phrase.
  static final String KEY =
      Base64.getEncoder()
          .encodeToString("local-test-qa-enc-key-0123456789".getBytes(StandardCharsets.UTF_8));
  static final String TEXT = "Năm nay chuyện tình duyên của tôi thế nào";

  final AesGcmQaCipherAdapter cipher = new AesGcmQaCipherAdapter(KEY, new SecureRandom());

  @Test
  void round_trips() {
    assertThat(cipher.decrypt("r:0", cipher.encrypt("r:0", TEXT))).isEqualTo(TEXT);
  }

  @Test
  void ciphertext_differs_each_time_and_hides_the_text() {
    byte[] a = cipher.encrypt("r:0", TEXT);

    assertThat(a).isNotEqualTo(cipher.encrypt("r:0", TEXT));
    assertThat(new String(a, StandardCharsets.UTF_8)).doesNotContain("tình duyên");
  }

  @Test
  void wrong_aad_fails() {
    byte[] sealed = cipher.encrypt("r:0", TEXT);

    assertThatThrownBy(() -> cipher.decrypt("r:1", sealed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(TEXT);
  }

  @Test
  void tampered_or_short_data_fails() {
    byte[] sealed = cipher.encrypt("r:0", TEXT);
    sealed[sealed.length - 1] ^= 1;

    assertThatThrownBy(() -> cipher.decrypt("r:0", sealed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher.decrypt("r:0", new byte[5]))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wrong_key_length_is_a_startup_failure() {
    String short16 = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new AesGcmQaCipherAdapter(short16, new SecureRandom()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QA_ENC_KEY");
    assertThatThrownBy(() -> new AesGcmQaCipherAdapter("", new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AesGcmQaCipherAdapter(null, new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AesGcmQaCipherAdapter("not base64!!", new SecureRandom()))
        .isInstanceOf(IllegalStateException.class);
  }
}
