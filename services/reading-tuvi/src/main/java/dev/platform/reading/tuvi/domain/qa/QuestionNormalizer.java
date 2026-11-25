package dev.platform.reading.tuvi.domain.qa;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical form of a question, for the cache key and keyword matching. */
public final class QuestionNormalizer {

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern TRAILING = Pattern.compile("[\\s?.!…]+$");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private QuestionNormalizer() {}

  /** NFC, trim, collapse whitespace, lowercase (ROOT), strip trailing {@code ?.!…}. */
  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    s = WHITESPACE.matcher(s).replaceAll(" ").toLowerCase(Locale.ROOT);
    return TRAILING.matcher(s).replaceAll("");
  }

  /** Lowercase text with Vietnamese diacritics removed ({@code đ} becomes {@code d}). */
  public static String fold(String text) {
    String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    return MARKS.matcher(s).replaceAll("").replace('đ', 'd');
  }
}
