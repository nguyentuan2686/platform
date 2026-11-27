package dev.platform.identity.adapter.out.crypto;

import dev.platform.identity.application.ClientKeyerPort;
import dev.platform.identity.domain.ClientKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 of the normalized client address, so a stored {@link ClientKey} cannot be reversed by
 * hashing every IPv4 address. The secret is derived from PROFILE_ENC_KEY (HMAC with a fixed label):
 * no new secret to manage, and the encryption key itself never hashes anything.
 */
public final class HmacClientKeyer implements ClientKeyerPort {

  private static final String HMAC = "HmacSHA256";
  private static final byte[] LABEL =
      "identity-svc magic-link client key v1".getBytes(StandardCharsets.US_ASCII);

  private final SecretKeySpec secret;

  /** Fails like the birth cipher when the key is not base64 of 32 bytes. */
  public HmacClientKeyer(String profileEncKeyBase64) {
    byte[] key;
    try {
      key =
          Base64.getDecoder()
              .decode(profileEncKeyBase64 == null ? "" : profileEncKeyBase64.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("PROFILE_ENC_KEY must be base64", e);
    }
    if (key.length != 32) {
      throw new IllegalStateException("PROFILE_ENC_KEY must be base64 of exactly 32 bytes");
    }
    this.secret = new SecretKeySpec(mac(new SecretKeySpec(key, HMAC), LABEL), HMAC);
  }

  @Override
  public ClientKey key(String normalizedAddress) {
    return new ClientKey(
        HexFormat.of()
            .formatHex(mac(secret, normalizedAddress.getBytes(StandardCharsets.US_ASCII))));
  }

  private static byte[] mac(SecretKeySpec key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is always available", e);
    }
  }
}
