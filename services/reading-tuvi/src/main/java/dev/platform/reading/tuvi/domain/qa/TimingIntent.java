package dev.platform.reading.tuvi.domain.qa;

import static dev.platform.reading.tuvi.domain.SafetyLexicon.word;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a question asks about time: {@code timing} for "when" questions ({@code khi nào}, {@code bao
 * giờ}, {@code năm nào} ...) and {@code targetYear} for a named year (an explicit year within the
 * next {@value #MAX_YEARS_AHEAD} years, or {@code năm nay}/{@code năm sau}/{@code năm tới}/{@code
 * sang năm} relative to the current year). Accented phrases match the {@link
 * QuestionNormalizer#matchForm}; folded phrases only the words typed without diacritics, so {@code
 * năm tôi} never reads as {@code nam toi}.
 */
public record TimingIntent(boolean timing, Optional<Integer> targetYear) {

  static final int MAX_YEARS_AHEAD = 15;

  public static final TimingIntent NONE = new TimingIntent(false, Optional.empty());

  private static final List<Pattern> TIMING_ACCENTED =
      compile(
          "khi nào",
          "bao giờ",
          "lúc nào",
          "năm nào",
          "năm mấy",
          "tháng nào",
          "tháng mấy",
          "bao lâu nữa",
          "mấy tuổi",
          "tuổi nào",
          "thời điểm",
          "sắp tới");
  private static final List<Pattern> TIMING_FOLDED =
      compile(
          "khi nao",
          "bao gio",
          "luc nao",
          "nam nao",
          "nam may",
          "thang nao",
          "thang may",
          "bao lau nua",
          "may tuoi",
          "tuoi nao",
          "thoi diem",
          "sap toi");
  private static final List<Pattern> NEXT_ACCENTED = compile("năm sau", "năm tới", "sang năm");
  private static final List<Pattern> NEXT_FOLDED = compile("nam sau", "nam toi", "sang nam");
  private static final List<Pattern> THIS_ACCENTED = compile("năm nay");
  private static final List<Pattern> THIS_FOLDED = compile("nam nay");
  private static final Pattern YEAR = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");

  public TimingIntent {
    targetYear = targetYear == null ? Optional.empty() : targetYear;
  }

  /** {@code question} in any form (it is normalized here); {@code currentYear} in Vietnam. */
  public static TimingIntent detect(String question, int currentYear) {
    String match = QuestionNormalizer.matchForm(question == null ? "" : question);
    String unaccented = QuestionNormalizer.unaccentedWords(match);
    boolean timing = any(TIMING_ACCENTED, match) || any(TIMING_FOLDED, unaccented);
    Optional<Integer> year = explicitYear(match, currentYear);
    if (year.isEmpty() && (any(NEXT_ACCENTED, match) || any(NEXT_FOLDED, unaccented))) {
      year = Optional.of(currentYear + 1);
    }
    if (year.isEmpty() && (any(THIS_ACCENTED, match) || any(THIS_FOLDED, unaccented))) {
      year = Optional.of(currentYear);
    }
    return new TimingIntent(timing, year);
  }

  private static Optional<Integer> explicitYear(String text, int currentYear) {
    Matcher m = YEAR.matcher(text);
    while (m.find()) {
      int y = Integer.parseInt(m.group(1));
      if (y >= currentYear && y <= currentYear + MAX_YEARS_AHEAD) {
        return Optional.of(y);
      }
    }
    return Optional.empty();
  }

  private static List<Pattern> compile(String... phrases) {
    return List.of(phrases).stream().map(p -> word(p)).toList();
  }

  private static boolean any(List<Pattern> patterns, String text) {
    return patterns.stream().anyMatch(p -> p.matcher(text).find());
  }
}
