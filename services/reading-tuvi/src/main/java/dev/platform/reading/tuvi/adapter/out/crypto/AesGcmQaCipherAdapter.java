package dev.platform.reading.tuvi.adapter.out.crypto;

import dev.platform.reading.tuvi.application.qa.QaTextCipherPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM, the scheme of identity-svc's {@code AesGcmBirthCipherAdapter}: a 12-byte random
 * nonce, then ciphertext and the 128-bit tag. The AAD ({@code <reading_id>:<seq>} or the cache key)
 * binds a ciphertext to its row. Errors never echo the key or the text.
 */
public class AesGcmQaCipherAdapter implements QaTextCipherPort {

  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random;

  /** Fails startup when the key is missing, not base64, or not 32 bytes. */
  public AesGcmQaCipherAdapter(String base64Key, SecureRandom random) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("QA_ENC_KEY must be base64");
    }
    if (raw.length != KEY_BYTES) {
      throw new IllegalStateException("QA_ENC_KEY must be base64 of exactly 32 bytes");
    }
    this.key = new SecretKeySpec(raw, "AES");
    Arrays.fill(raw, (byte) 0);
    this.random = random;
  }

  @Override
  public byte[] encrypt(String aad, String text) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    byte[] plain = text.getBytes(StandardCharsets.UTF_8);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plain);
      return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not encrypt Q&A text");
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  @Override
  public String decrypt(String aad, byte[] sealed) {
    if (sealed == null || sealed.length <= NONCE_BYTES) {
      throw new IllegalStateException("Could not decrypt Q&A text");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] plain = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException("Could not decrypt Q&A text");
    }
  }
}
