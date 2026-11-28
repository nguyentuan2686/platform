package dev.platform.reading.tuvi.adapter.out.crypto;

import dev.platform.reading.tuvi.application.number.NumberHasherPort;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 with {@code NUMBER_CHECK_HMAC_KEY}. An IPv6 address is reduced to its /64 (one home
 * or phone gets a whole /64) before hashing; IPv4 and anything else is hashed as given. Errors
 * never echo the key, the number or the IP.
 */
public class HmacNumberHasher implements NumberHasherPort {

  static final int MIN_KEY_CHARS = 16;
  private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

  private final SecretKeySpec key;

  /** Fails startup when the key is missing or shorter than 16 characters. */
  public HmacNumberHasher(String key) {
    if (key == null || key.strip().length() < MIN_KEY_CHARS) {
      throw new IllegalStateException("NUMBER_CHECK_HMAC_KEY must be set (at least 16 characters)");
    }
    this.key = new SecretKeySpec(key.strip().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Override
  public byte[] checkHash(String material) {
    return hmac(material);
  }

  @Override
  public byte[] ipHash(String ip) {
    return hmac(ipMaterial(ip));
  }

  /** {@code v6:<first 8 bytes hex>} for an IPv6 literal, else the stripped input. */
  static String ipMaterial(String ip) {
    String v = ip.strip();
    if (v.indexOf(':') >= 0 && IPV6.matcher(v).matches()) {
      try {
        // a literal with ':' is parsed without any DNS lookup
        byte[] a = InetAddress.getByName(v).getAddress();
        if (a.length == 16) {
          return "v6:" + HexFormat.of().formatHex(a, 0, 8);
        }
      } catch (UnknownHostException e) {
        // catch-ok: not a valid IPv6 literal (untrusted header), hashed as given
        return v;
      }
    }
    return v;
  }

  private byte[] hmac(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not compute the number-check HMAC");
    }
  }
}
