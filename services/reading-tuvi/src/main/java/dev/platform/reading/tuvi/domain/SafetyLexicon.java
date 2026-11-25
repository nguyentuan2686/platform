package dev.platform.reading.tuvi.domain;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared safety word lists (spec §15). {@link #BANNED} is the style-guide list used by both the
 * knowledge quality gate and the Q&A answer filter; the other lists are answer-filter rules. All
 * terms are lowercase NFC and are matched against lowercased NFC text.
 */
public final class SafetyLexicon {

  /** Terms never allowed in shown text (knowledge/tuvi/STYLE.md §2). */
  public static final Set<String> BANNED =
      Set.of(
          "chết",
          "tử vong",
          "tai nạn",
          "ung thư",
          "bệnh nặng",
          "giải hạn",
          "cúng",
          "bùa",
          "yểm",
          "phá sản",
          "ly hôn",
          "tuyệt tự",
          "đoản mệnh",
          "yểu",
          "suốt đời",
          "chắc chắn sẽ",
          "không tránh khỏi");

  /** Concrete financial, medical, legal or lottery advice. */
  public static final List<Pattern> ADVICE =
      List.of(
          word("nên (?:mua|bán|đầu tư|vay)"),
          word("(?:ngừng|bỏ) (?:thuốc|điều trị)"),
          word("không cần (?:đi khám|bác sĩ)"),
          word("nên kiện"),
          word("số (?:đẹp|may mắn) là"),
          Pattern.compile("(?<!\\d)\\d{2}\\s*[-–]\\s*\\d{2}(?!\\d)"));

  /** Fear selling and upselling. */
  public static final List<Pattern> FEAR =
      List.of(
          word("hạn nặng"),
          word("đại họa"),
          word("tai ương"),
          word("nạp tiền"),
          word("mua thêm lượt"),
          word("phải hóa giải"));

  /** Book citations (the answer must read as Lão Thiên Cơ's own words). */
  public static final List<Pattern> CITATION =
      List.of(word("theo sách"), word("toàn thư"), word("nguồn:"), word("trích"), lit("全書"));

  private SafetyLexicon() {}

  /** Han ideographs, CJK punctuation and full-width forms. */
  public static boolean isCjk(int cp) {
    return (cp >= 0x3000 && cp <= 0x303F) // CJK symbols and punctuation
        || (cp >= 0x3400 && cp <= 0x9FFF) // CJK ideographs (ext. A and unified)
        || (cp >= 0xF900 && cp <= 0xFAFF) // compatibility ideographs
        || (cp >= 0xFF00 && cp <= 0xFFEF) // halfwidth and fullwidth forms
        || (cp >= 0x20000 && cp <= 0x2FA1F); // supplementary ideographs
  }

  /** {@code regex} bounded by non-letters on both sides. */
  public static Pattern word(String regex) {
    return Pattern.compile("(?<!\\p{L})(?:" + regex + ")(?!\\p{L})");
  }

  private static Pattern lit(String text) {
    return Pattern.compile(Pattern.quote(text));
  }
}
