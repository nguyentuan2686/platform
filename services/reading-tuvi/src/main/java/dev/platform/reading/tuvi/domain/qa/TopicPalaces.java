package dev.platform.reading.tuvi.domain.qa;

import static dev.platform.reading.tuvi.domain.SafetyLexicon.word;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Which palaces ground an answer: Menh, the topic's own palaces, then keyword extras. */
public final class TopicPalaces {

  static final int MAX_PALACES = 5;

  private record Extra(Pattern phrase, String palace) {}

  private static final List<Extra> EXTRAS =
      List.of(
          extra("con cái|con trai|con gái", "TuTuc"),
          extra("nhà cửa|nhà đất|mua nhà", "DienTrach"),
          extra("cha mẹ|bố mẹ", "PhuMau"),
          extra("anh chị|anh em|chị em", "HuynhDe"),
          extra("bạn bè|đồng nghiệp|cấp dưới", "NoBoc"),
          extra("đi xa|xuất ngoại|du lịch|chuyển nhà", "ThienDi"),
          extra("vợ|chồng|người yêu", "PhuThe"));

  private TopicPalaces() {}

  private static Extra extra(String phrases, String palace) {
    return new Extra(word(phrases), palace);
  }

  /** {@code Menh} first, topic base list, keyword extras; distinct, at most five. */
  public static List<String> palaces(
      QaTopic topic, String normalizedQuestion, Optional<String> yearlyPalaceKey) {
    Set<String> out = new LinkedHashSet<>();
    out.add("Menh");
    out.addAll(base(topic, yearlyPalaceKey));
    String text =
        QuestionNormalizer.matchForm(normalizedQuestion == null ? "" : normalizedQuestion);
    for (Extra e : EXTRAS) {
      if (e.phrase().matcher(text).find()) {
        out.add(e.palace());
      }
    }
    List<String> list = new ArrayList<>(out);
    return List.copyOf(list.subList(0, Math.min(MAX_PALACES, list.size())));
  }

  private static List<String> base(QaTopic topic, Optional<String> yearlyPalaceKey) {
    return switch (topic) {
      case TinhDuyen -> List.of("PhuThe");
      case SuNghiep -> List.of("QuanLoc", "NoBoc");
      case TaiLoc -> List.of("TaiBach", "DienTrach");
      case SucKhoe -> List.of("TatAch");
      case GiaDao -> List.of("PhuMau", "HuynhDe", "TuTuc");
      case VanNam -> List.of(yearlyPalaceKey.orElse("ThienDi"));
    };
  }
}
