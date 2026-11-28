package dev.platform.reading.tuvi.domain.number;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a number against a profile on six criteria worth 10 points (spec §5.3). Weights,
 * thresholds and tables live here and in {@link Hexagram} / {@link FolkPairs}.
 */
public final class NumberScorer {

  static final double ELEMENT_MAX = 3.0;
  static final double HEXAGRAM_MAX = 2.5;
  static final double YINYANG_MAX = 1.5;
  static final double CUNGPHI_MAX = 1.5;
  static final double NUT_MAX = 0.5;
  static final double PAIRS_MAX = 1.0;
  static final double PAIRS_START = 0.5;
  static final double PAIR_STEP = 0.25;

  private NumberScorer() {}

  public static NumberScore score(NumberInput in, Profile p) {
    String d = in.digits();
    List<Criterion> criteria = new ArrayList<>();

    Element numberElement = numberElement(d);
    String relation = elementRelation(numberElement, p.element());
    criteria.add(
        new Criterion(
            "element",
            "Ngũ hành",
            elementDetail(numberElement, p.element(), relation),
            elementPoints(numberElement, p.element()),
            ELEMENT_MAX,
            "so:element=" + relation));

    Hexagram h = hexagram(d);
    criteria.add(
        new Criterion(
            "hexagram",
            "Quẻ Kinh Dịch",
            h.name(),
            hexagramPoints(h),
            HEXAGRAM_MAX,
            "so:que=" + h.id()));

    double yy = yinYangPoints(d);
    long even = d.chars().filter(c -> (c - '0') % 2 == 0).count();
    criteria.add(
        new Criterion(
            "yinyang",
            "Âm dương",
            even + " chẵn / " + (d.length() - even) + " lẻ",
            yy,
            YINYANG_MAX,
            "so:amduong=" + yinYangKey(yy)));

    int matching =
        (h.upper().east() == p.eastGroup() ? 1 : 0) + (h.lower().east() == p.eastGroup() ? 1 : 0);
    double cp = cungPhiPoints(h, p.eastGroup());
    criteria.add(
        new Criterion(
            "cungphi",
            "Cung phi",
            matching == 0 ? "Chưa hợp quái nào" : "Hợp " + matching + " quái",
            cp,
            CUNGPHI_MAX,
            "so:cungphi=" + cungPhiKey(matching)));

    int nut = nut(d);
    criteria.add(
        new Criterion(
            "nut", "Số nút", nut + " nút", nutPoints(nut), NUT_MAX, "so:nut=" + nutKey(nut)));

    List<String> good = goodFound(d);
    List<String> avoid = avoidFound(d);
    List<String> found = new ArrayList<>(good);
    found.addAll(avoid);
    criteria.add(
        new Criterion(
            "pairs",
            "Cặp số dân gian",
            found.isEmpty() ? "Không có" : String.join(", ", found),
            pairsPoints(d),
            PAIRS_MAX,
            null));

    double sum = criteria.stream().mapToDouble(Criterion::points).sum();
    double total = round(sum);
    return new NumberScore(total, Band.of(total), criteria, h, numberElement, good, avoid);
  }

  /** 0,1 Thủy; 2,5,8 Thổ; 3,4 Mộc; 6,7 Kim; 9 Hỏa. */
  static Element digitElement(char c) {
    return switch (c) {
      case '0', '1' -> Element.THUY;
      case '2', '5', '8' -> Element.THO;
      case '3', '4' -> Element.MOC;
      case '6', '7' -> Element.KIM;
      case '9' -> Element.HOA;
      default -> throw new IllegalArgumentException("not a digit");
    };
  }

  /** The element with the most digits; a tie goes to the element of the last tied digit. */
  static Element numberElement(String digits) {
    int[] counts = new int[Element.values().length];
    for (char c : digits.toCharArray()) {
      counts[digitElement(c).ordinal()]++;
    }
    int max = 0;
    for (int n : counts) {
      max = Math.max(max, n);
    }
    for (int i = digits.length() - 1; i >= 0; i--) {
      Element e = digitElement(digits.charAt(i));
      if (counts[e.ordinal()] == max) {
        return e;
      }
    }
    throw new IllegalArgumentException("empty number");
  }

  /** Knowledge outcome of the number's element against the mệnh element. */
  static String elementRelation(Element number, Element menh) {
    if (number.generates() == menh) {
      return "sinh_nhap";
    }
    if (number == menh) {
      return "tuong_hop";
    }
    if (menh.controls() == number) {
      return "khac_xuat";
    }
    if (menh.generates() == number) {
      return "sinh_xuat";
    }
    return "khac_nhap";
  }

  static double elementPoints(Element number, Element menh) {
    return switch (elementRelation(number, menh)) {
      case "sinh_nhap" -> 3.0;
      case "tuong_hop" -> 2.5;
      case "khac_xuat" -> 1.5;
      case "sinh_xuat" -> 1.0;
      default -> 0.0;
    };
  }

  private static String elementDetail(Element number, Element menh, String relation) {
    return switch (relation) {
      case "sinh_nhap" -> number.label() + " sinh " + menh.label();
      case "tuong_hop" -> number.label() + " hòa " + menh.label();
      case "khac_xuat" -> menh.label() + " khắc " + number.label();
      case "sinh_xuat" -> menh.label() + " sinh " + number.label();
      default -> number.label() + " khắc " + menh.label();
    };
  }

  /**
   * Upper = sum of the first half mod 8, lower = sum of the second half; 0 → 8; odd length: the
   * first half is shorter.
   */
  static Hexagram hexagram(String digits) {
    int split = digits.length() / 2;
    return Hexagram.of(trigram(digits.substring(0, split)), trigram(digits.substring(split)));
  }

  private static Trigram trigram(String part) {
    int r = digitSum(part) % 8;
    return Trigram.ofNumber(r == 0 ? 8 : r);
  }

  static double hexagramPoints(Hexagram h) {
    return switch (h.fortune()) {
      case CAT -> HEXAGRAM_MAX;
      case BINH -> HEXAGRAM_MAX / 2;
      case HUNG -> 0.0;
    };
  }

  /** Imbalance |even − odd| ≤ ceil(20% of length) → 1.5; ≤ ceil(40%) → 0.75; else 0. */
  static double yinYangPoints(String digits) {
    int len = digits.length();
    long even = digits.chars().filter(c -> (c - '0') % 2 == 0).count();
    long imbalance = Math.abs(even - (len - even));
    if (imbalance <= (len * 2 + 9) / 10) {
      return YINYANG_MAX;
    }
    return imbalance <= (len * 4 + 9) / 10 ? YINYANG_MAX / 2 : 0.0;
  }

  static String yinYangKey(double points) {
    if (points >= YINYANG_MAX) {
      return "can_bang";
    }
    return points > 0 ? "hoi_lech" : "lech";
  }

  /** Both trigrams in the person's Bát trạch group 1.5, one 0.75, none 0. */
  static double cungPhiPoints(Hexagram h, boolean east) {
    int matching = (h.upper().east() == east ? 1 : 0) + (h.lower().east() == east ? 1 : 0);
    return matching * (CUNGPHI_MAX / 2);
  }

  private static String cungPhiKey(int matching) {
    return switch (matching) {
      case 2 -> "hop_ca_hai";
      case 1 -> "hop_mot";
      default -> "chua_hop";
    };
  }

  /** Digit sum mod 10. */
  static int nut(String digits) {
    return digitSum(digits) % 10;
  }

  static double nutPoints(int nut) {
    if (nut == 9) {
      return NUT_MAX;
    }
    return nut == 7 || nut == 8 ? NUT_MAX / 2 : 0.0;
  }

  static String nutKey(int nut) {
    if (nut == 9) {
      return "cao";
    }
    return nut == 7 || nut == 8 ? "vua" : "thap";
  }

  /**
   * 0.5, +0.25 per good occurrence, −0.25 per avoid occurrence (overlaps count), clamped to [0, 1].
   */
  static double pairsPoints(String digits) {
    int good = 0;
    for (String g : FolkPairs.GOOD) {
      good += occurrences(digits, g);
    }
    int avoid = 0;
    for (String a : FolkPairs.AVOID) {
      avoid += occurrences(digits, a);
    }
    double raw = PAIRS_START + PAIR_STEP * (good - avoid);
    return Math.max(0.0, Math.min(PAIRS_MAX, raw));
  }

  /** Good entries present in the number, in table order. */
  static List<String> goodFound(String digits) {
    return FolkPairs.GOOD.stream().filter(digits::contains).toList();
  }

  /** Avoid entries present in the number, in table order. */
  static List<String> avoidFound(String digits) {
    return FolkPairs.AVOID.stream().filter(digits::contains).toList();
  }

  /** Nearest 0.5, a half step rounding up (9.25 → 9.5). */
  static double round(double total) {
    return Math.floor(total * 2 + 0.5) / 2;
  }

  private static int occurrences(String s, String sub) {
    int n = 0;
    for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) {
      n++;
    }
    return n;
  }

  private static int digitSum(String digits) {
    int s = 0;
    for (char c : digits.toCharArray()) {
      s += c - '0';
    }
    return s;
  }
}
