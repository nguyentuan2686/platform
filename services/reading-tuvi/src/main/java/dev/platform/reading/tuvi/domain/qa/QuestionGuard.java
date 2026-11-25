package dev.platform.reading.tuvi.domain.qa;

import static dev.platform.reading.tuvi.domain.SafetyLexicon.word;

import dev.platform.reading.tuvi.domain.qa.GuardResult.Invalid;
import dev.platform.reading.tuvi.domain.qa.GuardResult.Ok;
import dev.platform.reading.tuvi.domain.qa.GuardResult.Refused;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a user question may go to the model (spec §15).
 *
 * <p>The question first goes through {@link QuestionNormalizer#clean} and {@link PiiScrubber}; that
 * text is both what is matched and what is sent ({@code Ok.sanitized}), so nothing stripped later
 * can rebuild a blocked phrase. Matching uses {@link QuestionNormalizer#matchForm} with word
 * boundaries, so {@code vấn đề} is never gambling. Four lists per reason:
 *
 * <ul>
 *   <li><b>accented</b>: Vietnamese with diacritics, and English; always applied;
 *   <li><b>accented typing</b>: ASCII-looking Vietnamese terms ({@code vay}) that collide with an
 *       unaccented common word ({@code vậy}); applied only when most words carry diacritics;
 *   <li><b>unaccented</b>: folded phrases matched only against the words typed without diacritics
 *       (accented words are blanked), so {@code vậy tiền} never reads as {@code vay tien} while
 *       {@code toi muon chet nhé} still matches;
 *   <li><b>any</b>: folded phrases that stay unambiguous after folding (self-harm and injection),
 *       matched against the fully folded text, so a half-accented phrase cannot slip through.
 * </ul>
 *
 * {@link RefusalReason#SELF_HARM} is evaluated first, then the other reasons in enum order.
 */
public final class QuestionGuard {

  static final int MIN_LENGTH = 5;
  static final int MAX_LENGTH = 500;

  private static final Pattern VI_DIACRITIC =
      Pattern.compile("[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]");
  private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
  private static final String BLANK = "·";

  /** Professions a user may ask about as a career ("có hợp làm bác sĩ không"). */
  private static final String CAREER = "(?<!(?:làm|nghề|thành|học) )";

  private static final String CAREER_FOLDED = "(?<!(?:lam|nghe|thanh|hoc) )";

  private static final Map<RefusalReason, List<Pattern>> ACCENTED =
      new EnumMap<>(RefusalReason.class);
  private static final Map<RefusalReason, List<Pattern>> ACCENTED_TYPING =
      new EnumMap<>(RefusalReason.class);
  private static final Map<RefusalReason, List<Pattern>> UNACCENTED =
      new EnumMap<>(RefusalReason.class);
  private static final Map<RefusalReason, List<Pattern>> ANY_FOLDED =
      new EnumMap<>(RefusalReason.class);

  private static final List<RefusalReason> ORDER;

  static {
    selfHarm();
    medical();
    legal();
    finance();
    gambling();
    death();
    injection();
    List<RefusalReason> order = new ArrayList<>();
    order.add(RefusalReason.SELF_HARM);
    for (RefusalReason r : RefusalReason.values()) {
      if (r != RefusalReason.SELF_HARM && ACCENTED.containsKey(r)) {
        order.add(r);
      }
    }
    ORDER = List.copyOf(order);
  }

  private static void selfHarm() {
    List<String> anyFolded =
        List.of(
            // bare "tu sat" would also be the stars "tứ sát"/"Tử Sát"; only intent phrasings
            "(?:muon|dinh|nghi den|nghi toi|se|co nen) tu sat",
            "tu sat cho xong",
            "muon chet",
            "ket lieu",
            "khong muon song",
            "chan song",
            "chet di cho xong",
            "chet cho (?:roi|xong)",
            "khong thiet song",
            "thuoc ngu(?! hanh)",
            "nhay cau",
            "rach tay",
            "muon bien mat");
    List<String> unaccented = new ArrayList<>(anyFolded);
    unaccented.addAll(
        List.of(
            "(?:muon|dinh|nghi den) tu tu",
            "tu tu (?:di|cho xong|chet)",
            "song lam gi",
            "treo co",
            "cat tay"));
    add(
        RefusalReason.SELF_HARM,
        List.of(
            "tự tử",
            "tự sát",
            "muốn chết",
            "kết liễu",
            "tự hại",
            "tự làm hại",
            "không muốn sống",
            "chán sống",
            "chết đi cho xong",
            "chết cho (?:rồi|xong)",
            "không thiết sống",
            "sống làm gì",
            "thuốc ngủ",
            "nhảy cầu",
            "treo cổ",
            "rạch tay",
            "cắt tay",
            "muốn biến mất",
            "kill myself",
            "end my life",
            "want to die",
            "suicide"),
        List.of(),
        unaccented,
        anyFolded);
  }

  private static void medical() {
    add(
        RefusalReason.MEDICAL,
        List.of(
            "thuốc",
            "chữa bệnh",
            "chữa trị",
            "điều trị",
            "phẫu thuật",
            "xét nghiệm",
            CAREER + "bác sĩ",
            "kê đơn",
            "chẩn đoán",
            "khám bệnh",
            "bệnh viện",
            "ung thư",
            "tiểu đường",
            "huyết áp"),
        List.of(),
        List.of(
            "uong thuoc",
            "dung thuoc",
            "thuoc chua",
            "thuoc tri",
            "chua benh",
            "dieu tri",
            "phau thuat",
            "xet nghiem",
            CAREER_FOLDED + "bac si",
            "ke don",
            "chan doan",
            "benh vien",
            "ung thu"),
        List.of());
  }

  private static void legal() {
    add(
        RefusalReason.LEGAL,
        List.of(
            "(?<!(?:sự|điều|bưu|linh|phụ|văn|bộ|dữ|chứng|vật|tài) )kiện(?! (?:toàn|tướng))",
            CAREER + "luật sư",
            "ra tòa",
            "hầu tòa",
            "tòa án",
            "tranh chấp đất",
            "tranh chấp tài sản",
            "lawsuit"),
        List.of(),
        List.of(
            "kien tung",
            "kien cao",
            "khoi kien",
            "di kien",
            CAREER_FOLDED + "luat su",
            "ra toa",
            "hau toa",
            "toa an",
            "tranh chap dat"),
        List.of());
  }

  private static void finance() {
    add(
        RefusalReason.FINANCE,
        List.of(
            "cổ phiếu",
            "chứng khoán",
            "crypto",
            "bitcoin",
            "coin",
            "forex",
            "tiền ảo",
            "tiền điện tử",
            "mua vàng",
            "lãi suất",
            "đầu tư vào",
            "vay (?:tiền|nợ|vốn|ngân hàng|lãi|nóng|mua)",
            "(?:đi|khoản|món) vay"),
        // bare "vay" collides with unaccented "vậy"
        List.of("vay"),
        List.of(
            "co phieu",
            "chung khoan",
            "tien ao",
            "tien dien tu",
            "mua vang",
            "vay (?:tien|no|von|ngan hang|lai|nong)",
            "di vay",
            "khoan vay",
            "lai suat",
            "dau tu vao"),
        List.of());
  }

  private static void gambling() {
    add(
        RefusalReason.GAMBLING,
        List.of(
            "lô đề",
            "đánh đề",
            "số đề",
            "xổ số",
            "vietlott",
            "lô tô",
            "bao lô",
            "cá độ",
            "cá cược",
            "casino",
            "đánh bài",
            "con số may mắn",
            "lottery"),
        List.of(),
        List.of(
            "lo de", "danh de", "so de", "xo so", "ca do", "ca cuoc", "danh bai", "con so may man"),
        List.of());
  }

  private static void death() {
    add(
        RefusalReason.DEATH,
        List.of(
            "khi nào chết",
            "bao giờ chết",
            "lúc nào chết",
            "chết khi nào",
            "chết năm nào",
            "sống được bao lâu",
            "sống bao lâu",
            "sống đến bao nhiêu tuổi",
            "tuổi thọ",
            "thọ bao nhiêu",
            "qua đời"),
        List.of(),
        List.of(
            "khi nao chet",
            "bao gio chet",
            "luc nao chet",
            "chet khi nao",
            "chet nam nao",
            "song duoc bao lau",
            "song bao lau",
            "song den bao nhieu tuoi",
            "qua doi"),
        List.of());
  }

  private static void injection() {
    List<String> english =
        List.of(
            "(?:ignore|disregard) (?:all|any|previous|prior|the|these|those|everything|your"
                + "|above|earlier)",
            "forget (?:(?:all|your|the|previous|these|prior) )*(?:instructions|rules|prompts?"
                + "|guidelines)",
            "system prompt",
            "act as",
            "pretend to be",
            "you are now",
            "developer mode",
            "jailbreak");
    List<String> folded =
        List.of(
            "(?:bo qua|quen|phot lo) (?:moi|cac|tat ca|nhung|het) (?:huong dan|chi dan|quy tac"
                + "|lenh)",
            "loi nhac he thong",
            "tiet lo (?:prompt|huong dan|loi nhac)",
            "dong vai(?! tro)",
            "nhap vai",
            "gia vo la",
            "ban la mot",
            "cau hoi:",
            "tu lieu:");
    List<String> accented = new ArrayList<>(english);
    accented.addAll(
        List.of(
            "(?:bỏ qua|quên|phớt lờ) (?:mọi|các|tất cả|những|hết) (?:hướng dẫn|chỉ dẫn|quy tắc"
                + "|lệnh)",
            "lời nhắc hệ thống",
            "tiết lộ (?:prompt|hướng dẫn|lời nhắc)",
            "đóng vai(?! trò)",
            "nhập vai",
            "giả vờ là",
            "bạn là một",
            "câu hỏi:",
            "tư liệu:"));
    List<String> anyFolded = new ArrayList<>(folded);
    anyFolded.addAll(english);
    add(RefusalReason.INJECTION, accented, List.of(), folded, anyFolded);
  }

  private QuestionGuard() {}

  private static void add(
      RefusalReason reason,
      List<String> accented,
      List<String> accentedTyping,
      List<String> unaccented,
      List<String> anyFolded) {
    ACCENTED.put(reason, compile(accented));
    ACCENTED_TYPING.put(reason, compile(accentedTyping));
    UNACCENTED.put(reason, compile(unaccented));
    ANY_FOLDED.put(reason, compile(anyFolded));
  }

  private static List<Pattern> compile(List<String> terms) {
    return terms.stream().map(t -> word(t)).toList();
  }

  public static GuardResult check(String raw) {
    if (raw == null || raw.length() > MAX_LENGTH * 8) {
      return new Invalid("question");
    }
    String cleaned = QuestionNormalizer.clean(raw);
    if (cleaned.length() < MIN_LENGTH || cleaned.length() > MAX_LENGTH) {
      return new Invalid("question");
    }
    String sanitized = PiiScrubber.scrub(cleaned);
    String match = QuestionNormalizer.matchForm(sanitized);
    String folded = QuestionNormalizer.fold(match);
    String[] words = match.split(" ");
    StringBuilder unaccentedWords = new StringBuilder(match.length());
    int letterWords = 0;
    int accentedWords = 0;
    for (String w : words) {
      boolean accented = VI_DIACRITIC.matcher(w).find();
      if (HAS_LETTER.matcher(w).find()) {
        letterWords++;
        accentedWords += accented ? 1 : 0;
      }
      if (!unaccentedWords.isEmpty()) {
        unaccentedWords.append(' ');
      }
      unaccentedWords.append(accented ? BLANK : w);
    }
    boolean accentedTyping = accentedWords > 0 && accentedWords * 2 >= letterWords;
    String unaccented = unaccentedWords.toString();
    for (RefusalReason reason : ORDER) {
      if (matches(ACCENTED.get(reason), match)
          || (accentedTyping && matches(ACCENTED_TYPING.get(reason), match))
          || matches(UNACCENTED.get(reason), unaccented)
          || matches(ANY_FOLDED.get(reason), folded)) {
        return new Refused(reason);
      }
    }
    return new Ok(sanitized);
  }

  private static boolean matches(List<Pattern> patterns, String text) {
    for (Pattern p : patterns) {
      if (p.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }
}
