package dev.platform.payment.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds order codes in free bank-transfer text. Banks glue words together and change case, so the
 * text is upper-cased (prefixes TV for tuvi, CT for the reserved astro site; TC is kept from the
 * original spec) and every substring shaped like a code is a candidate, with no word boundaries
 * (the lookahead also finds overlapping candidates).
 */
public final class OrderCodeMatcher {

  private static final Pattern CODE = Pattern.compile("(?=((?:T[VC]|CT)[2-9A-HJKMNP-Z]{6}))");

  private OrderCodeMatcher() {}

  public static Set<String> candidates(String content) {
    Set<String> found = new LinkedHashSet<>();
    if (content == null || content.isBlank()) {
      return found;
    }
    Matcher m = CODE.matcher(content.toUpperCase(Locale.ROOT));
    while (m.find()) {
      found.add(m.group(1));
    }
    return found;
  }
}
