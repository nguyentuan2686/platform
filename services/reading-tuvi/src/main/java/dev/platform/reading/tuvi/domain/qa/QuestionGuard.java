package dev.platform.reading.tuvi.domain.qa;

import static dev.platform.reading.tuvi.domain.SafetyLexicon.word;

import dev.platform.reading.tuvi.domain.qa.GuardResult.Invalid;
import dev.platform.reading.tuvi.domain.qa.GuardResult.Ok;
import dev.platform.reading.tuvi.domain.qa.GuardResult.Refused;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a user question may go to the model (spec §15). Matching runs on the normalized
 * text with word boundaries, so {@code vấn đề} is never gambling. Questions typed without
 * diacritics are also checked against a folded list of unambiguous phrases (single folded words
 * such as {@code thuoc} or {@code vay} would hit {@code thuộc} or {@code vậy}). {@link
 * RefusalReason#SELF_HARM} is evaluated first, then the other reasons in enum order.
 */
public final class QuestionGuard {

  static final int MIN_LENGTH = 5;
  static final int MAX_LENGTH = 500;

  /** Control characters except newline, and invisible format characters (zero-width etc.). */
  private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");

  private static final Pattern DELIMITERS = Pattern.compile("<<<|>>>|`");
  private static final Pattern VI_DIACRITIC =
      Pattern.compile("[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]");

  /** Professions a user may ask about as a career ("có hợp làm bác sĩ không"). */
  private static final String CAREER = "(?<!(?:làm|nghề|thành|học) )";

  private static final String CAREER_FOLDED = "(?<!(?:lam|nghe|thanh|hoc) )";

  private static final Map<RefusalReason, List<Pattern>> ACCENTED =
      new EnumMap<>(RefusalReason.class);
  private static final Map<RefusalReason, List<Pattern>> FOLDED =
      new EnumMap<>(RefusalReason.class);

  private static final List<RefusalReason> ORDER;

  static {
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
            "kill myself",
            "suicide"),
        List.of(
            "(?:muon|dinh|nghi den|se|co nen|nen) tu tu",
            "tu sat",
            "muon chet",
            "ket lieu",
            "tu hai",
            "khong muon song",
            "chan song"));
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
            "ung thu"));
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
        List.of(
            "kien tung",
            "kien cao",
            "khoi kien",
            "di kien",
            CAREER_FOLDED + "luat su",
            "ra toa",
            "hau toa",
            "toa an",
            "tranh chap dat"));
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
            "vay",
            "lãi suất",
            "đầu tư vào"),
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
            "dau tu vao"));
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
        List.of(
            "lo de",
            "danh de",
            "so de",
            "xo so",
            "ca do",
            "ca cuoc",
            "danh bai",
            "con so may man"));
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
        List.of(
            "khi nao chet",
            "bao gio chet",
            "luc nao chet",
            "chet khi nao",
            "chet nam nao",
            "song duoc bao lau",
            "song bao lau",
            "song den bao nhieu tuoi",
            "qua doi"));
    add(
        RefusalReason.INJECTION,
        List.of(
            "(?:bỏ qua|quên|phớt lờ) (?:mọi|các|tất cả|những|hết) (?:hướng dẫn|chỉ dẫn|quy tắc|lệnh)",
            "(?:ignore|disregard|forget) (?:all|any|previous|prior|the above|your)",
            "system prompt",
            "lời nhắc hệ thống",
            "tiết lộ (?:prompt|hướng dẫn|lời nhắc)",
            "đóng vai",
            "nhập vai",
            "giả vờ là",
            "act as",
            "pretend to be",
            "you are now",
            "developer mode",
            "jailbreak",
            "bạn là một",
            "câu hỏi:",
            "tư liệu:"),
        List.of(
            "(?:bo qua|quen|phot lo) (?:moi|cac|tat ca|nhung|het) (?:huong dan|chi dan|quy tac|lenh)",
            "loi nhac he thong",
            "tiet lo (?:prompt|huong dan|loi nhac)",
            "dong vai",
            "nhap vai",
            "gia vo la",
            "ban la mot",
            "cau hoi:",
            "tu lieu:"));
    List<RefusalReason> order = new ArrayList<>();
    order.add(RefusalReason.SELF_HARM);
    for (RefusalReason r : RefusalReason.values()) {
      if (r != RefusalReason.SELF_HARM && ACCENTED.containsKey(r)) {
        order.add(r);
      }
    }
    ORDER = List.copyOf(order);
  }

  private QuestionGuard() {}

  private static void add(RefusalReason reason, List<String> accented, List<String> folded) {
    ACCENTED.put(reason, accented.stream().map(t -> word(t)).toList());
    FOLDED.put(reason, folded.stream().map(t -> word(t)).toList());
  }

  public static GuardResult check(String raw) {
    if (raw == null) {
      return new Invalid("question");
    }
    String trimmed = raw.strip();
    if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
      return new Invalid("question");
    }
    String visible =
        INVISIBLE.matcher(Normalizer.normalize(trimmed, Normalizer.Form.NFC)).replaceAll("");
    String normalized = QuestionNormalizer.normalize(visible);
    boolean unaccented = !VI_DIACRITIC.matcher(normalized).find();
    String folded = QuestionNormalizer.fold(normalized);
    for (RefusalReason reason : ORDER) {
      if (matches(ACCENTED.get(reason), normalized)
          || (unaccented && matches(FOLDED.get(reason), folded))) {
        return new Refused(reason);
      }
    }
    String sanitized = DELIMITERS.matcher(visible).replaceAll("");
    sanitized = PiiScrubber.scrub(sanitized).strip().replaceAll("[ \\t]{2,}", " ");
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
