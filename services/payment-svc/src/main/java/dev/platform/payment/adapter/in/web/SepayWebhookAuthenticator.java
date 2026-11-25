package dev.platform.payment.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates SePay webhooks. Mode {@code apikey}: header {@code Authorization: Apikey <secret>}.
 * Mode {@code hmac-sha256}: header {@code X-SePay-Signature} is the hex HMAC-SHA256 of the raw body
 * under the secret. Comparisons are constant-time; an unset secret rejects everything. The secret
 * is never logged or put in a message.
 */
public class SepayWebhookAuthenticator {

  public static final String SIGNATURE_HEADER = "X-SePay-Signature";
  private static final String APIKEY_PREFIX = "Apikey ";

  private final boolean hmac;
  private final byte[] secret;

  public SepayWebhookAuthenticator(String mode, String secret) {
    this.hmac =
        switch (mode) {
          case "apikey" -> false;
          case "hmac-sha256" -> true;
          default ->
              throw new IllegalStateException("SEPAY_WEBHOOK_AUTH must be apikey or hmac-sha256");
        };
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public boolean authenticate(String authorization, String signature, byte[] body) {
    if (secret.length == 0) {
      return false; // not configured: fail closed
    }
    return hmac ? checkSignature(signature, body) : checkApikey(authorization);
  }

  private boolean checkApikey(String authorization) {
    if (authorization == null || !authorization.startsWith(APIKEY_PREFIX)) {
      return false;
    }
    byte[] presented =
        authorization.substring(APIKEY_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
    return constantTimeEquals(presented, secret);
  }

  private boolean checkSignature(String signature, byte[] body) {
    if (signature == null || signature.isEmpty()) {
      return false;
    }
    byte[] expected = hex(mac(body));
    byte[] presented = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    return constantTimeEquals(presented, expected);
  }

  /** Compares digests so that neither the content nor the length of the secret leaks. */
  private static boolean constantTimeEquals(byte[] a, byte[] b) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return MessageDigest.isEqual(sha.digest(a), sha.digest(b));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private byte[] mac(byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private static byte[] hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes).getBytes(StandardCharsets.UTF_8);
  }
}
