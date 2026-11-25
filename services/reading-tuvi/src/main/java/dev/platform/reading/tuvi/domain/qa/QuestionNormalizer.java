package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.SafetyLexicon;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One text pipeline for questions. {@link #clean} gives the text that is sent to the model; {@link
 * #normalize} (cache key) and {@link #matchForm} (keyword matching) are derived from it, so the
 * guard always sees what the model will see.
 */
public final class QuestionNormalizer {

  private static final Pattern CRLF = Pattern.compile("\\r\\n?");
  private static final Pattern SPACE_LIKE = Pattern.compile("[\\p{Z}\\t\\f\\x0B]");
  private static final Pattern DELIMITERS = Pattern.compile("<<<|>>>|`");
  private static final Pattern SPACES = Pattern.compile(" {2,}");
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
  private static final Pattern TRAILING = Pattern.compile("[\\s?.!…]+$");
  private static final Pattern IN_WORD_PUNCT = Pattern.compile("(?<=\\p{L})[-_.·/]+(?=\\p{L})");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private QuestionNormalizer() {}

  /**
   * The text sent to the model: NFKC (folds full-width forms), Cyrillic/Greek look-alikes mapped to
   * Latin, invisible characters dropped, every Unicode space or tab turned into one space, newlines
   * kept, and the prompt delimiters {@code <<<}, {@code >>>} and backticks removed.
   */
  public static String clean(String text) {
    if (text == null) {
      return "";
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
    s = SafetyLexicon.mapLookAlikes(s);
    s = CRLF.matcher(s).replaceAll("\n");
    s = SPACE_LIKE.matcher(s).replaceAll(" ");
    s = SafetyLexicon.stripInvisible(s);
    s = DELIMITERS.matcher(s).replaceAll("");
    s = SPACES.matcher(s).replaceAll(" ");
    return s.strip();
  }

  /**
   * Cache key: {@link #clean}, whitespace collapsed, lowercase (ROOT), trailing {@code ?.!…} cut.
   */
  public static String normalize(String text) {
    String s = WHITESPACE.matcher(clean(text)).replaceAll(" ").toLowerCase(Locale.ROOT);
    return TRAILING.matcher(s).replaceAll("");
  }

  /**
   * Keyword-matching form: {@link #normalize}, old-style tone placement unified ({@code hoá} reads
   * as {@code hóa}), and hyphens, underscores, dots or slashes between letters read as spaces.
   */
  public static String matchForm(String text) {
    String s = SafetyLexicon.canonicalTones(normalize(text));
    return IN_WORD_PUNCT.matcher(s).replaceAll(" ");
  }

  /** Lowercase text with Vietnamese diacritics removed ({@code đ} becomes {@code d}). */
  public static String fold(String text) {
    String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    return MARKS.matcher(s).replaceAll("").replace('đ', 'd');
  }
}
