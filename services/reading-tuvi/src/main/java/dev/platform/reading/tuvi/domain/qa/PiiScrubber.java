package dev.platform.reading.tuvi.domain.qa;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Replaces personal data in a question with {@code [đã ẩn]} before it reaches an LLM: emails, phone
 * numbers, dates, clock times, and any run of six or more digits (ID, card and account numbers).
 */
public final class PiiScrubber {

  static final String HIDDEN = "[đã ẩn]";

  private static final List<Pattern> PATTERNS =
      List.of(
          // emails
          Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}"),
          // Vietnamese phone numbers
          Pattern.compile("(?<![\\d+])(?:\\+?84|0)[\\s.-]?\\d(?:[\\s.-]?\\d){7,9}(?!\\d)"),
          // numeric dates: 12/03/1994, 1-3-94, 12.03.1994
          Pattern.compile("(?<!\\d)\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}(?!\\d)"),
          // spelled dates: ngày 12 tháng 3 (năm 1994)
          Pattern.compile(
              "(?iu)ngày\\s+\\d{1,2}\\s+tháng\\s+\\d{1,2}(?:\\s+năm\\s+\\d{2,4})?(?!\\d)"),
          // clock times (birth hour): 8h30, 08:30, 8 giờ 30
          Pattern.compile("(?iu)(?<!\\d)\\d{1,2}\\s*(?:h|:|giờ)\\s*\\d{2}(?!\\d)"),
          // any run of 6+ digits
          Pattern.compile("\\d{6,}"));

  private PiiScrubber() {}

  public static String scrub(String text) {
    if (text == null) {
      return "";
    }
    String out = text;
    for (Pattern p : PATTERNS) {
      out = p.matcher(out).replaceAll(HIDDEN);
    }
    return out;
  }
}
