package dev.platform.reading.tuvi.domain.qa;

import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.Palace;
import dev.platform.reading.tuvi.domain.ReadingPlan;
import dev.platform.reading.tuvi.domain.StarPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Selects the palaces for a topic and turns them into lookup chains and Vietnamese facts. */
public final class QaContextBuilder {

  private static final Map<String, String> BRIGHTNESS_VI =
      Map.of("Mieu", "Miếu", "Vuong", "Vượng", "Dac", "Đắc", "Binh", "Bình", "Ham", "Hãm");
  private static final Map<String, String> MUTAGEN_VI =
      Map.of("Loc", "Lộc", "Quyen", "Quyền", "Khoa", "Khoa", "Ky", "Kỵ");

  private QaContextBuilder() {}

  /** Without timing intent: the yearly layer counts only for {@link QaTopic#VanNam} (this year). */
  public static QaContext build(
      Chart chart, Optional<FlowLayer> yearly, QaTopic topic, String normalizedQuestion) {
    return build(chart, yearly, topic, normalizedQuestion, TimingIntent.NONE, 0);
  }

  /**
   * {@code yearly} is the flow layer of the target year when {@code intent} names one, else of the
   * current year; it is used for {@link QaTopic#VanNam} or a named year. A timing question also
   * gets the {@code THỜI VẬN} lines, computed from {@code currentYear} (Vietnam).
   */
  public static QaContext build(
      Chart chart,
      Optional<FlowLayer> yearly,
      QaTopic topic,
      String normalizedQuestion,
      TimingIntent intent,
      int currentYear) {
    Optional<FlowLayer> layer =
        topic == QaTopic.VanNam || intent.targetYear().isPresent() ? yearly : Optional.empty();
    Optional<FlowLayer> palaceLayer = topic == QaTopic.VanNam ? layer : Optional.empty();
    List<String> keys =
        TopicPalaces.palaces(topic, normalizedQuestion, palaceLayer.map(FlowLayer::palaceKey));
    List<List<KnowledgeKey>> chains = new ArrayList<>();
    List<PalaceFact> facts = new ArrayList<>();
    for (String key : keys) {
      Palace p = chart.palace(key);
      ReadingPlan.palaceLines(chart, p).forEach(line -> chains.add(line.lookupOrder()));
      facts.add(
          new PalaceFact(
              p.nameVi(),
              p.major().stream().map(QaContextBuilder::renderStar).toList(),
              chart.inTuan(p),
              chart.inTriet(p)));
    }
    String yearLabel =
        intent.targetYear().filter(y -> y != currentYear).map(y -> "Năm " + y).orElse("Năm nay");
    List<String> timing = intent.timing() ? TimingLines.lines(chart, keys, currentYear) : List.of();
    return new QaContext(
        List.copyOf(chains),
        List.copyOf(facts),
        layer.map(l -> yearlyFact(chart, l, yearLabel)),
        currentYear,
        timing);
  }

  /** {@code "<nameVi> (<brightness>[, Hóa <x>])"}; parentheses are dropped when empty. */
  static String renderStar(StarPlacement s) {
    List<String> notes = new ArrayList<>();
    if (s.brightness() != null) {
      notes.add(BRIGHTNESS_VI.getOrDefault(s.brightness(), s.brightness()));
    }
    if (s.mutagen() != null) {
      notes.add("Hóa " + mutagenVi(s.mutagen()));
    }
    return notes.isEmpty() ? s.nameVi() : s.nameVi() + " (" + String.join(", ", notes) + ")";
  }

  private static String mutagenVi(String mutagen) {
    return MUTAGEN_VI.getOrDefault(mutagen, mutagen);
  }

  private static String yearlyFact(Chart chart, FlowLayer layer, String yearLabel) {
    String text = yearLabel + " lưu niên ở cung " + chart.palace(layer.palaceKey()).nameVi();
    if (layer.mutagens().isEmpty()) {
      return text;
    }
    List<String> parts =
        layer.mutagens().stream()
            .map(m -> starName(chart, m.star()) + " Hóa " + mutagenVi(m.mutagen()))
            .toList();
    return text + "; Tứ Hóa năm: " + String.join(", ", parts);
  }

  private static String starName(Chart chart, String starKey) {
    for (Palace p : chart.palaces()) {
      for (List<StarPlacement> group : List.of(p.major(), p.minor())) {
        for (StarPlacement s : group) {
          if (s.key().equals(starKey)) {
            return s.nameVi();
          }
        }
      }
    }
    return starKey;
  }
}
