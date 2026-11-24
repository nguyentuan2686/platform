package dev.platform.reading.tuvi.application;

import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.Level;
import dev.platform.reading.tuvi.domain.ReadingPlan;
import dev.platform.reading.tuvi.domain.TodayScorer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders readings from the chart service's facts and the knowledge base (no LLM, no invention).
 */
public class ReadingService implements BasicReadingUseCase, TodayUseCase {

  /**
   * Version of the rendered shape; part of the cache key. Bump it on ANY change to {@link
   * ReadingPlan}, {@link TemplateRenderer} or the section/line shape, so stale cached readings are
   * not served. 2: consecutive {@code missing} lines collapse into one.
   */
  public static final int RENDER_VERSION = 2;

  private static final Logger LOG = LoggerFactory.getLogger(ReadingService.class);

  static final String CHARACTER = "LaoThienCo";
  static final String LANG = "vi";
  private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private static final Map<Area, String> LABELS =
      Map.of(
          Area.CongViec, "Công việc",
          Area.DiLai, "Đi lại",
          Area.TienBac, "Tiền bạc",
          Area.SucKhoe, "Sức khỏe");

  private final ChartPort charts;
  private final KnowledgePort knowledge;
  private final ReadingCachePort cache;
  private final int renderVersion;

  public ReadingService(ChartPort charts, KnowledgePort knowledge, ReadingCachePort cache) {
    this(charts, knowledge, cache, RENDER_VERSION);
  }

  ReadingService(
      ChartPort charts, KnowledgePort knowledge, ReadingCachePort cache, int renderVersion) {
    this.charts = charts;
    this.knowledge = knowledge;
    this.cache = cache;
    this.renderVersion = renderVersion;
  }

  @Override
  public BasicReading basic(Birth birth) {
    ChartResult result = charts.chart(birth);
    long version = knowledge.version();
    Optional<BasicReading> cached = cacheFind(result.chartHash(), version);
    if (cached.isPresent()) {
      return cached.get();
    }
    BasicReading reading = renderBasic(result, version);
    cachePut(reading);
    return reading;
  }

  // The cache is best-effort: a failure is logged (exception type only) and never fails a reading.
  private Optional<BasicReading> cacheFind(String chartHash, long version) {
    try {
      return cache.find(chartHash, version, renderVersion);
    } catch (RuntimeException e) {
      LOG.warn("Reading cache lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private void cachePut(BasicReading reading) {
    try {
      cache.put(reading, renderVersion);
    } catch (RuntimeException e) {
      LOG.warn("Reading cache store failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public TodayReading today(Birth birth, LocalDate date) {
    Chart chart = charts.chart(birth).chart();
    DailyResult day = charts.daily(birth, date);
    Map<Area, Level> levels = TodayScorer.score(chart, day.facts());

    Map<String, String> vars =
        Map.of(
            "date", DISPLAY_DATE.format(date),
            "lunar", day.lunar(),
            "canchi", day.dayCanChi());
    List<TodayReading.AreaLine> areas = new ArrayList<>();
    for (Area area : Area.values()) {
      Level level = levels.get(area);
      areas.add(
          new TodayReading.AreaLine(
              area,
              LABELS.get(area),
              level,
              renderFirst(List.of(KnowledgeKey.daily(area, level)), Map.of())));
    }
    return new TodayReading(
        date,
        day.lunar(),
        day.dayCanChi(),
        CHARACTER,
        renderFirst(List.of(KnowledgeKey.frame("today_opening")), vars),
        areas,
        renderFirst(List.of(KnowledgeKey.frame("today_closing")), Map.of()));
  }

  private BasicReading renderBasic(ChartResult result, long version) {
    List<BasicReading.RenderedSection> sections = new ArrayList<>();
    for (ReadingPlan.Section s : ReadingPlan.basic(result.chart())) {
      String title =
          s.titleFrame()
              .flatMap(frame -> find(KnowledgeKey.frame(frame)))
              .map(t -> TemplateRenderer.render(t, s.vars()))
              .orElse(null);
      List<String> lines = new ArrayList<>();
      boolean previousMissing = false;
      for (ReadingPlan.Line line : s.lines()) {
        Rendered r = renderLine(line.lookupOrder(), line.vars());
        if (r.missing() && previousMissing) {
          continue;
        }
        previousMissing = r.missing();
        lines.add(r.text());
      }
      sections.add(new BasicReading.RenderedSection(s.id(), title, lines));
    }
    return new BasicReading(CHARACTER, result.school(), result.chartHash(), version, sections);
  }

  /** First key with a knowledge record wins; none found renders the {@code missing} frame. */
  private String renderFirst(List<KnowledgeKey> lookupOrder, Map<String, String> vars) {
    return renderLine(lookupOrder, vars).text();
  }

  private Rendered renderLine(List<KnowledgeKey> lookupOrder, Map<String, String> vars) {
    for (KnowledgeKey key : lookupOrder) {
      Optional<String> text = find(key);
      if (text.isPresent()) {
        return new Rendered(TemplateRenderer.render(text.get(), vars), false);
      }
    }
    return new Rendered(
        find(KnowledgeKey.frame("missing"))
            .map(t -> TemplateRenderer.render(t, Map.of()))
            .orElse(""),
        true);
  }

  private record Rendered(String text, boolean missing) {}

  private Optional<String> find(KnowledgeKey key) {
    return knowledge.find(key, LANG);
  }
}
