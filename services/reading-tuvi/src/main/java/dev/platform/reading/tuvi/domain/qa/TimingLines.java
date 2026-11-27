package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.Palace;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code THỜI VẬN} lines of a timing question, in calendar years computed here (calendar year =
 * lunar birth year + nominal age - 1), so the model never has to derive a year. Never contains a
 * birth date, hour, gender or age: only palace names and year ranges.
 */
public final class TimingLines {

  /** How many years ahead (current year included) the lưu niên scan looks. */
  static final int FLOW_YEARS = 12;

  /** Chart-svc branch ids, in order: year Y has branch {@code BRANCHES[(Y - 4) mod 12]}. */
  private static final List<String> BRANCHES =
      List.of("Zi", "Chou", "Yin", "Mao", "Chen", "Si", "Wu", "Wei", "Shen", "You", "Xu", "Hai");

  private static final List<String> BRANCHES_VI =
      List.of("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi");

  /** Year Y has stem {@code STEMS_VI[(Y - 4) mod 10]}. */
  private static final List<String> STEMS_VI =
      List.of("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý");

  private TimingLines() {}

  /**
   * The current đại hạn, then for every palace in {@code palaceKeys} except Mệnh its đại hạn and
   * the next year (from {@code currentYear}) whose branch is the palace's branch.
   */
  public static List<String> lines(Chart chart, List<String> palaceKeys, int currentYear) {
    int birthYear = chart.lunarBirthYear();
    int age = currentYear - birthYear + 1;
    List<String> out = new ArrayList<>();
    chart.palaces().stream()
        .filter(p -> p.decade().contains(age))
        .findFirst()
        .ifPresent(
            p ->
                out.add(
                    "Đại hạn hiện tại (năm "
                        + currentYear
                        + "): cung "
                        + p.nameVi()
                        + ", năm "
                        + range(birthYear, p.decade())));
    for (String key : palaceKeys) {
      if (key.equals("Menh")) {
        continue;
      }
      Palace p = chart.palace(key);
      out.add(
          "Đại hạn cung "
              + p.nameVi()
              + ": năm "
              + range(birthYear, p.decade())
              + status(birthYear, p.decade(), currentYear));
      flowYear(p.branch(), currentYear)
          .ifPresent(
              y ->
                  out.add(
                      "Lưu niên vào cung " + p.nameVi() + ": năm " + y + " (" + canChi(y) + ")"));
    }
    return List.copyOf(out);
  }

  /** Can Chi of a calendar year in Vietnamese, e.g. {@code Tân Hợi} for 2031. */
  static String canChi(int year) {
    return STEMS_VI.get(Math.floorMod(year - 4, 10))
        + " "
        + BRANCHES_VI.get(Math.floorMod(year - 4, 12));
  }

  /** The first year in {@code [from, from + FLOW_YEARS)} with the given chart-svc branch. */
  static Optional<Integer> flowYear(String branch, int from) {
    for (int y = from; y < from + FLOW_YEARS; y++) {
      if (BRANCHES.get(Math.floorMod(y - 4, 12)).equals(branch)) {
        return Optional.of(y);
      }
    }
    return Optional.empty();
  }

  private static String range(int birthYear, Palace.Decade d) {
    return (birthYear + d.from() - 1) + "–" + (birthYear + d.to() - 1);
  }

  private static String status(int birthYear, Palace.Decade d, int currentYear) {
    if (birthYear + d.to() - 1 < currentYear) {
      return " (đã qua)";
    }
    return d.contains(currentYear - birthYear + 1) ? " (đang đi)" : "";
  }
}
