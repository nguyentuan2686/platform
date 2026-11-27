package dev.platform.identity.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The part of a client IP literal the per-client mail cap counts on: the whole IPv4 address, or the
 * IPv6 /64 prefix (one subscriber usually holds a whole /64, so a finer key would be free to
 * rotate). IPv4-mapped IPv6 counts as the IPv4 address.
 */
public final class ClientAddress {

  private static final int MAX_INPUT = 64;
  private static final Pattern IPV4 =
      Pattern.compile(
          "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])([.](25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");
  private static final Pattern HEXTET = Pattern.compile("[0-9a-f]{1,4}");
  private static final String MAPPED_V4 = "::ffff:";

  private ClientAddress() {}

  /**
   * {@code v4:<address>} or {@code v6:<first four hextets>}, one spelling per client; empty for
   * anything that is not an IP literal.
   */
  public static Optional<String> normalize(String rawIp) {
    if (rawIp == null || rawIp.length() > MAX_INPUT) {
      return Optional.empty();
    }
    String ip = rawIp.strip().toLowerCase(Locale.ROOT);
    if (ip.startsWith(MAPPED_V4) && IPV4.matcher(ip.substring(MAPPED_V4.length())).matches()) {
      ip = ip.substring(MAPPED_V4.length());
    }
    if (IPV4.matcher(ip).matches()) {
      return Optional.of("v4:" + ip);
    }
    return ipv6Prefix64(ip).map(p -> "v6:" + p);
  }

  private static Optional<String> ipv6Prefix64(String ip) {
    if (!ip.contains(":")) {
      return Optional.empty();
    }
    int gap = ip.indexOf("::");
    if (gap >= 0 && gap != ip.lastIndexOf("::")) {
      return Optional.empty();
    }
    String[] head = split(gap >= 0 ? ip.substring(0, gap) : ip);
    String[] tail = gap >= 0 ? split(ip.substring(gap + 2)) : new String[0];
    if (head == null || tail == null) {
      return Optional.empty();
    }
    if (gap >= 0 ? head.length + tail.length > 7 : head.length != 8) {
      return Optional.empty();
    }
    int[] words = new int[8];
    for (int i = 0; i < head.length; i++) {
      words[i] = Integer.parseInt(head[i], 16);
    }
    for (int i = 0; i < tail.length; i++) {
      words[8 - tail.length + i] = Integer.parseInt(tail[i], 16);
    }
    return Optional.of(String.format("%x:%x:%x:%x", words[0], words[1], words[2], words[3]));
  }

  /** Colon-separated hextets ("" = none); null when a part is not 1-4 hex digits. */
  private static String[] split(String part) {
    if (part.isEmpty()) {
      return new String[0];
    }
    String[] parts = part.split(":", -1);
    for (String p : parts) {
      if (!HEXTET.matcher(p).matches()) {
        return null;
      }
    }
    return parts;
  }
}
