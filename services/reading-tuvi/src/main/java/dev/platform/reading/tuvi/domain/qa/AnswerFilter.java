package dev.platform.reading.tuvi.domain.qa;

import static dev.platform.reading.tuvi.domain.SafetyLexicon.word;

import dev.platform.reading.tuvi.domain.SafetyLexicon;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Pass;
import dev.platform.reading.tuvi.domain.qa.FilterResult.Reject;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks a model answer before it is shown or charged (spec §15). Markdown headings and emphasis
 * and numeric citation markers are cleaned first; then the rules run in a fixed order and the first
 * broken rule is reported by name. The answer text is never part of the result.
 */
public final class AnswerFilter {

  private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*#{1,6}[ \\t]*");
  private static final Pattern EMPHASIS = Pattern.compile("\\*\\*|__");
  private static final Pattern CITATION_MARK = Pattern.compile("[ \\t]*\\[\\d+\\]");
  private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
  private static final Pattern LINE_END_SPACES = Pattern.compile("(?m)[ \\t]+$");

  /** Prompt markers and sentinels; uppercase markers are matched case-sensitively. */
  private static final List<String> LEAK_LITERALS =
      List.of(
          "TƯ LIỆU", "CÂU HỎI", "<<<", ">>>", Sentinel.NGOAI_PHAM_VI, Sentinel.KHONG_DU_TU_LIEU);

  private static final List<Pattern> LEAK_WORDS =
      List.of(
          word("system"),
          word("hướng dẫn hệ thống"),
          // prompt section headers in any case: "Tư liệu:", "Chủ đề :"
          word("(?:tư liệu|câu hỏi|chủ đề)\\s*[:：]"));

  private static final List<Pattern> CONTACT =
      List.of(
          Pattern.compile(
              "(?i)(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}-]+\\.(?:vn|com|me|net|org|io|link|info|xyz)"
                  + "(?![\\p{L}\\p{N}])"),
          Pattern.compile("(?i)(?:https?://|www\\.)\\S+"),
          Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.\\p{L}{2,}"),
          Pattern.compile("(?<![\\d+])(?:\\+?84|0)[\\s.-]?\\d(?:[\\s.-]?\\d){7,9}(?!\\d)"));

  private static final Pattern VI_DIACRITIC =
      Pattern.compile("[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]");
  private static final Pattern VOICE = word("con");

  private AnswerFilter() {}

  public static FilterResult check(String answer, AnswerLimits limits) {
    String cleaned = clean(answer);
    String lower = cleaned.toLowerCase(Locale.ROOT);
    // matching form: old-style tone placement unified (hoá giải reads as hóa giải)
    String match = SafetyLexicon.canonicalTones(lower);
    if (SafetyLexicon.BANNED.stream()
        .anyMatch(t -> match.contains(SafetyLexicon.canonicalTones(t)))) {
      return new Reject("banned");
    }
    if (any(SafetyLexicon.ADVICE, match)) {
      return new Reject("advice");
    }
    if (any(SafetyLexicon.FEAR, match)) {
      return new Reject("fear");
    }
    if (any(SafetyLexicon.CITATION, match)) {
      return new Reject("citation");
    }
    if (LEAK_LITERALS.stream().anyMatch(cleaned::contains) || any(LEAK_WORDS, match)) {
      return new Reject("leak");
    }
    if (any(CONTACT, cleaned)) {
      return new Reject("contact");
    }
    if (cleaned.codePoints().anyMatch(SafetyLexicon::isCjk)) {
      return new Reject("cjk");
    }
    if (!VI_DIACRITIC.matcher(lower).find()) {
      return new Reject("diacritic");
    }
    if (!VOICE.matcher(lower).find()) {
      return new Reject("voice");
    }
    if (cleaned.length() < limits.min() || cleaned.length() > limits.max()) {
      return new Reject("length");
    }
    return new Pass(cleaned);
  }

  private static String clean(String answer) {
    if (answer == null) {
      return "";
    }
    String s = SafetyLexicon.stripInvisible(Normalizer.normalize(answer, Normalizer.Form.NFC));
    s = HEADING.matcher(s).replaceAll("");
    s = EMPHASIS.matcher(s).replaceAll("");
    s = CITATION_MARK.matcher(s).replaceAll("");
    s = SPACES.matcher(s).replaceAll(" ");
    s = LINE_END_SPACES.matcher(s).replaceAll("");
    return s.strip();
  }

  private static boolean any(List<Pattern> patterns, String text) {
    for (Pattern p : patterns) {
      if (p.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }
}
