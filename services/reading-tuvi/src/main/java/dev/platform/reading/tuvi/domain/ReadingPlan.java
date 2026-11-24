package dev.platform.reading.tuvi.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the ordered, doctrine-free skeleton of a basic reading. Each {@link Line} lists knowledge
 * keys in priority order: the first key with a knowledge record wins; if none exists the caller
 * uses the {@code tuvi:frame=missing} frame (never invented text).
 */
public final class ReadingPlan {

  /** Palaces after Menh in the reading's fixed (Menh-relative) order, not chart (branch) order. */
  private static final List<String> AFTER_MENH =
      List.of(
          "PhuMau",
          "PhucDuc",
          "DienTrach",
          "QuanLoc",
          "NoBoc",
          "ThienDi",
          "TatAch",
          "TaiBach",
          "TuTuc",
          "PhuThe",
          "HuynhDe");

  private ReadingPlan() {}

  public record Section(String id, String titleFrame, List<Line> lines) {}

  public record Line(List<KnowledgeKey> lookupOrder, Map<String, String> vars) {}

  public static List<Section> basic(Chart chart) {
    List<Section> out = new ArrayList<>();
    out.add(frameSection("greeting", "greeting", Map.of()));
    out.add(palaceSection("menh", chart, chart.palace("Menh")));
    Palace than = chart.palace(chart.thanPalace());
    out.add(frameSection("than", "than_intro", Map.of("palace", than.nameVi())));
    out.add(frameSection("cuc", "cuc_intro", Map.of("cuc", chart.cucNameVi())));
    for (String key : AFTER_MENH) {
      out.add(palaceSection(key, chart, chart.palace(key)));
    }
    out.add(frameSection("closing", "closing", Map.of()));
    out.add(frameSection("disclaimer", "disclaimer", Map.of()));
    return out;
  }

  private static Section frameSection(String id, String frame, Map<String, String> vars) {
    return new Section(
        id, "title_" + id, List.of(new Line(List.of(KnowledgeKey.frame(frame)), vars)));
  }

  private static Section palaceSection(String id, Chart chart, Palace p) {
    String key = p.key();
    Map<String, String> palaceVar = Map.of("palace", p.nameVi());
    List<Line> lines = new ArrayList<>();
    lines.add(new Line(List.of(KnowledgeKey.palace(key)), palaceVar));
    if (p.major().isEmpty()) {
      lines.add(new Line(List.of(KnowledgeKey.palaceNoMajor(key)), palaceVar));
    }
    for (StarPlacement s : p.major()) {
      List<KnowledgeKey> order = new ArrayList<>();
      if (s.brightness() != null) {
        order.add(KnowledgeKey.star(s.key(), key, s.brightness()));
      }
      order.add(KnowledgeKey.star(s.key(), key));
      order.add(KnowledgeKey.star(s.key()));
      lines.add(
          new Line(
              order,
              Map.of(
                  "palace",
                  p.nameVi(),
                  "star",
                  s.nameVi(),
                  "brightness",
                  s.brightness() == null ? "" : s.brightness())));
    }
    List<StarPlacement> all = new ArrayList<>(p.major());
    all.addAll(p.minor());
    for (StarPlacement s : all) {
      if (s.mutagen() != null) {
        lines.add(
            new Line(
                List.of(KnowledgeKey.palaceTuhoa(key, s.mutagen())),
                Map.of("palace", p.nameVi(), "star", s.nameVi(), "tuhoa", s.mutagen())));
      }
    }
    if (chart.inTuan(p)) {
      lines.add(new Line(List.of(KnowledgeKey.palaceMark(key, "Tuan")), palaceVar));
    }
    if (chart.inTriet(p)) {
      lines.add(new Line(List.of(KnowledgeKey.palaceMark(key, "Triet")), palaceVar));
    }
    return new Section(id, "title_" + snake(id), lines);
  }

  private static String snake(String camel) {
    return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }
}
