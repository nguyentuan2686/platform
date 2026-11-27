package dev.platform.reading.tuvi.adapter.out.chart;

import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.DailyFacts;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.Palace;
import dev.platform.reading.tuvi.domain.StarPlacement;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Maps chart-svc JSON (the parts a reading needs) to domain records. */
public final class ChartMapper {

  private ChartMapper() {}

  public static Chart chart(JsonNode root) {
    List<Palace> palaces = new ArrayList<>();
    for (JsonNode p : root.get("palaces")) {
      palaces.add(
          new Palace(
              p.get("key").asString(),
              p.get("nameVi").asString(),
              p.get("branch").asString(),
              p.get("isBody").asBoolean(),
              decade(p.get("decade")),
              stars(p.get("major")),
              stars(p.get("minor")),
              p.get("tuan").asBoolean(),
              p.get("triet").asBoolean()));
    }
    return new Chart(
        root.get("cuc").get("nameVi").asString(),
        root.get("thanPalace").asString(),
        strings(root.get("tuan")),
        strings(root.get("triet")),
        palaces,
        root.get("lunar").get("year").asInt());
  }

  public static String school(JsonNode root) {
    return root.get("school").asString();
  }

  public static DailyFacts daily(JsonNode root) {
    return new DailyFacts(
        layer(root.get("yearly")), layer(root.get("monthly")), layer(root.get("daily")));
  }

  /** Lunar day/month as {@code d/m}, with {@code nhuận} appended for a leap month. */
  public static String lunar(JsonNode root) {
    JsonNode l = root.get("lunar");
    String text = l.get("day").asInt() + "/" + l.get("month").asInt();
    return l.get("leap").asBoolean() ? text + " nhuận" : text;
  }

  public static String dayCanChi(JsonNode root) {
    return root.get("dayCanChi").get("nameVi").asString();
  }

  private static FlowLayer layer(JsonNode n) {
    List<FlowLayer.Mutagen> mutagens = new ArrayList<>();
    for (JsonNode m : n.get("mutagens")) {
      mutagens.add(new FlowLayer.Mutagen(m.get("star").asString(), m.get("mutagen").asString()));
    }
    return new FlowLayer(
        n.get("palaceKey").asString(),
        n.get("branch").asString(),
        n.get("stem").asString(),
        mutagens);
  }

  private static Palace.Decade decade(JsonNode n) {
    return new Palace.Decade(n.get("from").asInt(), n.get("to").asInt());
  }

  private static List<StarPlacement> stars(JsonNode arr) {
    List<StarPlacement> out = new ArrayList<>();
    for (JsonNode s : arr) {
      out.add(
          new StarPlacement(
              s.get("key").asString(),
              s.get("nameVi").asString(),
              s.get("brightness").isNull() ? null : s.get("brightness").asString(),
              s.get("mutagen").isNull() ? null : s.get("mutagen").asString()));
    }
    return out;
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    arr.forEach(n -> out.add(n.asString()));
    return out;
  }
}
