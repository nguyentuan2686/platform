package dev.platform.reading.tuvi.domain;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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

  /**
   * {@code regex} bounded by non-letters on both sides, with its tone placement made canonical (see
   * {@link #canonicalTones}); match it against canonical text.
   */
  public static Pattern word(String regex) {
    return Pattern.compile("(?<!\\p{L})(?:" + canonicalTones(regex) + ")(?!\\p{L})");
  }

  /**
   * Unifies the two Vietnamese tone-placement conventions at the end of a syllable: {@code oá} →
   * {@code óa}, {@code oẹ} → {@code ọe}, {@code uỷ} → {@code ủy} (so {@code hoá}, {@code hoạ},
   * {@code toà}, {@code khoẻ}, {@code thuỷ} read as {@code hóa}, {@code họa}, {@code tòa}, {@code
   * khỏe}, {@code thủy}). For matching only; lowercase input.
   */
  public static String canonicalTones(String text) {
    Matcher m = Tones.OLD_STYLE.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    while (m.find()) {
      char base = m.group().charAt(0);
      char toned = m.group().charAt(1);
      int tone = Tones.toneOf(toned);
      String carrier = base == 'o' ? Tones.O_TONES : Tones.U_TONES;
      char plain = Tones.plainOf(toned);
      m.appendReplacement(out, Matcher.quoteReplacement(carrier.charAt(tone) + "" + plain));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Drops invisible characters: format characters (zero-width etc.), variation selectors, CGJ. */
  public static String stripInvisible(String text) {
    return Tones.INVISIBLE.matcher(text).replaceAll("");
  }

  /** Maps Cyrillic and Greek letters that look like Latin ones to their Latin twins. */
  public static String mapLookAlikes(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int at = Tones.LOOK_ALIKE_FROM.indexOf(c);
      out.append(at < 0 ? c : Tones.LOOK_ALIKE_TO.charAt(at));
    }
    return out.toString();
  }

  /** Holder, so these exist before the lexicon fields above are compiled. */
  private static final class Tones {
    // index 0 = grave, 1 = acute, 2 = hook, 3 = tilde, 4 = dot
    static final String A_TONES = "àáảãạ";
    static final String E_TONES = "èéẻẽẹ";
    static final String Y_TONES = "ỳýỷỹỵ";
    static final String O_TONES = "òóỏõọ";
    static final String U_TONES = "ùúủũụ";
    static final Pattern OLD_STYLE =
        Pattern.compile("(?:o[" + A_TONES + E_TONES + "]|u[" + Y_TONES + "])(?!\\p{L})");
    static final Pattern INVISIBLE =
        Pattern.compile(
            "[\\p{Cf}\\x{FE00}-\\x{FE0F}\\x{E0100}-\\x{E01EF}\\x{034F}]|[\\p{Cc}&&[^\\n]]");
    // Cyrillic a e o p c y x i dze je komi-de shha palochka, upper A Ve E Ka Em En O Er Es Te Ha
    // I Dze Je; Greek omicron nu alpha epsilon iota kappa rho tau upsilon chi, and capitals.
    static final String LOOK_ALIKE_FROM =
        "аеорсухіѕјԁһӏ" + "АВЕКМНОРСТХІЅЈ" + "οναεικρτυχ" + "ΑΒΕΖΗΙΚΜΝΟΡΤΥΧ";
    static final String LOOK_ALIKE_TO =
        "aeopcyxisjdhl" + "ABEKMHOPCTXISJ" + "ovaeikptux" + "ABEZHIKMNOPTYX";

    static int toneOf(char toned) {
      for (String set : List.of(A_TONES, E_TONES, Y_TONES)) {
        int i = set.indexOf(toned);
        if (i >= 0) {
          return i;
        }
      }
      throw new IllegalArgumentException("not a toned vowel");
    }

    static char plainOf(char toned) {
      if (A_TONES.indexOf(toned) >= 0) {
        return 'a';
      }
      return E_TONES.indexOf(toned) >= 0 ? 'e' : 'y';
    }
  }

  private static Pattern lit(String text) {
    return Pattern.compile(Pattern.quote(text));
  }
}
