package dev.platform.reading.tuvi.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * Scores the four "Hôm nay" areas (spec 14.1; the weights are our own culture/entertainment
 * heuristic). Per area palace: daily-layer mutagens on stars sitting in that natal palace (Loc +2,
 * Quyen +1, Khoa +1, Ky -2); monthly-layer mutagens likewise (Loc +1, Quyen 0, Khoa 0, Ky -1); +1
 * if the daily layer's palace is the area palace; -1 if the palace lies in Triet; -1 if in Tuan.
 * Score &gt;= 2 is {@code Thuan}, &lt;= -2 is {@code LuuY}, otherwise {@code Binh}.
 */
public final class TodayScorer {

  private TodayScorer() {}

  public static Map<Area, Level> score(Chart natal, DailyFacts day) {
    Map<Area, Level> out = new EnumMap<>(Area.class);
    for (Area area : Area.values()) {
      out.put(area, level(points(natal, day, area)));
    }
    return out;
  }

  private static int points(Chart natal, DailyFacts day, Area area) {
    Palace p = natal.palace(area.palaceKey());
    int score =
        layerPoints(p, day.daily(), 2, 1, 1, -2) + layerPoints(p, day.monthly(), 1, 0, 0, -1);
    if (area.palaceKey().equals(day.daily().palaceKey())) {
      score += 1;
    }
    if (natal.inTriet(p)) {
      score -= 1;
    }
    if (natal.inTuan(p)) {
      score -= 1;
    }
    return score;
  }

  private static int layerPoints(Palace p, FlowLayer layer, int loc, int quyen, int khoa, int ky) {
    int sum = 0;
    for (FlowLayer.Mutagen m : layer.mutagens()) {
      if (!sits(p, m.star())) {
        continue;
      }
      sum +=
          switch (m.mutagen()) {
            case "Loc" -> loc;
            case "Quyen" -> quyen;
            case "Khoa" -> khoa;
            case "Ky" -> ky;
            default -> 0;
          };
    }
    return sum;
  }

  private static boolean sits(Palace p, String star) {
    return p.major().stream().anyMatch(s -> s.key().equals(star))
        || p.minor().stream().anyMatch(s -> s.key().equals(star));
  }

  private static Level level(int score) {
    if (score >= 2) {
      return Level.Thuan;
    }
    if (score <= -2) {
      return Level.LuuY;
    }
    return Level.Binh;
  }
}
