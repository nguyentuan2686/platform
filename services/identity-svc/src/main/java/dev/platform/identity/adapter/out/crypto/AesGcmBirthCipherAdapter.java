package dev.platform.identity.adapter.out.crypto;

import dev.platform.identity.application.BirthCipherPort;
import dev.platform.identity.application.ProfileCipherException;
import dev.platform.identity.domain.BirthData;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM. Layout: 12-byte random nonce, then ciphertext and 128-bit tag. The profile id is the
 * associated data, so a ciphertext cannot be moved to another profile.
 */
public class AesGcmBirthCipherAdapter implements BirthCipherPort {

  static final int KEY_ID = 1;
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final char SEPARATOR = '\n';

  private final SecretKeySpec key;
  private final SecureRandom random;

  /** Fails startup when the key is missing, not base64, or not 32 bytes. Never echoes the key. */
  public AesGcmBirthCipherAdapter(String base64Key, SecureRandom random) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("PROFILE_ENC_KEY must be base64");
    }
    if (raw.length != KEY_BYTES) {
      throw new IllegalStateException("PROFILE_ENC_KEY must be base64 of exactly 32 bytes");
    }
    this.key = new SecretKeySpec(raw, "AES");
    Arrays.fill(raw, (byte) 0);
    this.random = random;
  }

  @Override
  public int currentKeyId() {
    return KEY_ID;
  }

  @Override
  public byte[] encrypt(UUID profileId, BirthData birth) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    byte[] plain =
        (birth.birth() + SEPARATOR + birth.zone() + SEPARATOR + birth.gender())
            .getBytes(StandardCharsets.UTF_8);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(profileId));
      byte[] sealed = cipher.doFinal(plain);
      return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new ProfileCipherException("Could not encrypt profile data", e);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  @Override
  public BirthData decrypt(UUID profileId, byte[] sealed) {
    if (sealed == null || sealed.length <= NONCE_BYTES) {
      throw new ProfileCipherException("Could not decrypt profile data", null);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      cipher.updateAAD(aad(profileId));
      byte[] plain = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
      try {
        String[] parts = new String(plain, StandardCharsets.UTF_8).split(String.valueOf(SEPARATOR));
        return new BirthData(parts[0], parts[1], parts[2]);
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new ProfileCipherException("Could not decrypt profile data", null);
    }
  }

  private static byte[] aad(UUID profileId) {
    return ByteBuffer.allocate(16)
        .putLong(profileId.getMostSignificantBits())
        .putLong(profileId.getLeastSignificantBits())
        .array();
  }
}
