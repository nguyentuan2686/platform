package dev.platform.reading.tuvi.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.reading.tuvi.adapter.out.chart.ChartMapper;
import dev.platform.reading.tuvi.domain.Area;
import dev.platform.reading.tuvi.domain.Chart;
import dev.platform.reading.tuvi.domain.DailyFacts;
import dev.platform.reading.tuvi.domain.FlowLayer;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import dev.platform.reading.tuvi.domain.Level;
import dev.platform.reading.tuvi.domain.TodayScorer;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReadingServiceTest {

  static final String MISSING = "MISSING-FRAME";
  static final Birth BIRTH = new Birth("1994-03-12T08:30", null, "male", null);

  final Map<String, String> store = new HashMap<>();
  final List<String> findCalls = new ArrayList<>();
  final Map<String, BasicReading> cache = new HashMap<>();
  int chartCalls;
  boolean failCache;
  long version = 1;
  Chart chart;

  final ChartPort charts =
      new ChartPort() {
        @Override
        public ChartResult chart(Birth birth) {
          chartCalls++;
          return new ChartResult(chart, "hash-1", "school-x");
        }

        @Override
        public DailyResult daily(Birth birth, LocalDate date) {
          FlowLayer layer = new FlowLayer("Menh", "Yin", "Jia", List.of());
          return new DailyResult(new DailyFacts(layer, layer, layer), "13/10", "Giáp Tý");
        }
      };

  final KnowledgePort knowledge =
      new KnowledgePort() {
        @Override
        public Optional<String> find(KnowledgeKey key, String lang) {
          findCalls.add(key.value());
          return Optional.ofNullable(store.get(key.value()));
        }

        @Override
        public long version() {
          return version;
        }
      };

  final ReadingCachePort cachePort =
      new ReadingCachePort() {
        @Override
        public Optional<BasicReading> find(
            String chartHash, long knowledgeVersion, int renderVersion) {
          if (failCache) {
            throw new IllegalStateException("boom");
          }
          return Optional.ofNullable(
              cache.get(chartHash + "/" + knowledgeVersion + "/" + renderVersion));
        }

        @Override
        public void put(BasicReading r, int renderVersion) {
          if (failCache) {
            throw new IllegalStateException("boom");
          }
          cache.put(r.chartHash() + "/" + r.knowledgeVersion() + "/" + renderVersion, r);
        }
      };

  final ReadingService service = new ReadingService(charts, knowledge, cachePort);

  @BeforeEach
  void setUp() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/chart-fixture.json")) {
      chart = ChartMapper.chart(JsonMapper.shared().readTree(in));
    }
    store.put("tuvi:frame=missing", MISSING);
  }

  @Test
  void empty_store_renders_every_section_with_the_missing_frame() {
    BasicReading r = service.basic(BIRTH);

    assertThat(r.sections()).hasSize(17);
    assertThat(r.sections().getLast().id()).isEqualTo("disclaimer");
    assertThat(r.sections()).allSatisfy(s -> assertThat(s.lines()).containsExactly(MISSING));
    assertThat(r.character()).isEqualTo("LaoThienCo");
    assertThat(r.school()).isEqualTo("school-x");
    assertThat(r.chartHash()).isEqualTo("hash-1");
    assertThat(r.knowledgeVersion()).isEqualTo(1);
  }

  @Test
  void exact_star_record_is_used_and_renders_placeholders() {
    store.put("tuvi:star=ThienTuong:palace=Menh:brightness=Dac", "Sao {star} tại cung {palace}.");

    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "menh").lines()).contains("Sao Thiên Tướng tại cung Mệnh.");
  }

  @Test
  void star_only_record_is_the_fallback() {
    store.put("tuvi:star=ThienTuong", "Thiên Tướng nói chung.");

    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "menh").lines()).contains("Thiên Tướng nói chung.");
  }

  @Test
  void more_specific_record_wins_over_the_fallback() {
    store.put("tuvi:star=ThienTuong", "general");
    store.put("tuvi:star=ThienTuong:palace=Menh", "in menh");

    assertThat(section(service.basic(BIRTH), "menh").lines()).contains("in menh");
  }

  @Test
  void titles_render_the_frame_with_section_vars_and_are_null_without_a_title_frame() {
    store.put("tuvi:frame=menh_title", "Cung Mệnh");
    store.put("tuvi:frame=palace_title", "Cung {palace}");

    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "menh").title()).isEqualTo("Cung Mệnh");
    assertThat(section(r, "PhuMau").title()).isEqualTo("Cung Phụ Mẫu");
    assertThat(section(r, "greeting").title()).isNull();
    assertThat(section(r, "than").title()).isNull(); // than_title frame not in the store
  }

  @Test
  void unknown_placeholder_renders_empty_and_no_brace_survives() {
    store.put("tuvi:frame=greeting", "Chào {nobody}con {oops và { lạc }");

    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "greeting").lines()).containsExactly("Chào con oops và  lạc ");
    assertThat(r.sections())
        .allSatisfy(s -> assertThat(String.join("", s.lines())).doesNotContain("{", "}"));
  }

  @Test
  void second_call_with_same_chart_and_version_is_a_cache_hit() {
    service.basic(BIRTH);
    int finds = findCalls.size();
    assertThat(finds).isPositive();

    BasicReading again = service.basic(BIRTH);

    assertThat(chartCalls).isEqualTo(2);
    assertThat(findCalls).hasSize(finds);
    assertThat(again.sections()).hasSize(17);
  }

  @Test
  void a_new_knowledge_version_misses_the_cache() {
    service.basic(BIRTH);
    store.put("tuvi:frame=greeting", "mới");
    version = 2;

    BasicReading r = service.basic(BIRTH);

    assertThat(r.knowledgeVersion()).isEqualTo(2);
    assertThat(section(r, "greeting").lines()).containsExactly("mới");
  }

  @Test
  void a_different_render_version_misses_the_cache() {
    service.basic(BIRTH);
    int finds = findCalls.size();

    new ReadingService(charts, knowledge, cachePort, ReadingService.RENDER_VERSION + 1)
        .basic(BIRTH);

    assertThat(findCalls.size()).isGreaterThan(finds);
    assertThat(cache).hasSize(2);
  }

  @Test
  void consecutive_missing_lines_collapse_into_one() {
    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "menh").lines()).containsExactly(MISSING);
    assertThat(r.sections()).hasSize(17);
  }

  @Test
  void a_failing_cache_is_best_effort_and_still_returns_the_reading() {
    failCache = true;

    BasicReading r = service.basic(BIRTH);

    assertThat(r.sections()).hasSize(17);
  }

  @Test
  void today_lists_four_areas_in_enum_order_with_daily_lines_and_rendered_frames() {
    for (Area a : Area.values()) {
      for (Level l : Level.values()) {
        store.put(KnowledgeKey.daily(a, l).value(), "line-" + a + "-" + l);
      }
    }
    store.put("tuvi:frame=today_opening", "Ngày {date}, âm {lunar}, {canchi}; cung {palace}.");
    store.put("tuvi:frame=today_closing", "Hết.");
    LocalDate date = LocalDate.of(2026, 11, 24);
    Map<Area, Level> expected = TodayScorer.score(chart, charts.daily(BIRTH, date).facts());

    TodayReading r = service.today(BIRTH, date);

    assertThat(r.areas()).extracting(TodayReading.AreaLine::area).containsExactly(Area.values());
    assertThat(r.areas())
        .extracting(TodayReading.AreaLine::label)
        .containsExactly("Công việc", "Đi lại", "Tiền bạc", "Sức khỏe");
    for (TodayReading.AreaLine a : r.areas()) {
      assertThat(a.level()).isEqualTo(expected.get(a.area()));
      assertThat(a.line()).isEqualTo("line-" + a.area() + "-" + expected.get(a.area()));
    }
    assertThat(r.opening()).isEqualTo("Ngày 24/11/2026, âm 13/10, Giáp Tý; cung .");
    assertThat(r.closing()).isEqualTo("Hết.");
    assertThat(r.lunar()).isEqualTo("13/10");
    assertThat(r.dayCanChi()).isEqualTo("Giáp Tý");
    assertThat(r.character()).isEqualTo("LaoThienCo");
  }

  @Test
  void today_with_missing_daily_records_uses_the_missing_frame() {
    TodayReading r = service.today(BIRTH, LocalDate.of(2026, 11, 24));

    assertThat(r.areas()).extracting(TodayReading.AreaLine::line).containsOnly(MISSING);
    assertThat(r.opening()).isEqualTo(MISSING);
  }

  @Test
  void a_missing_frame_that_is_itself_absent_renders_empty_never_an_error() {
    store.clear();

    BasicReading r = service.basic(BIRTH);

    assertThat(section(r, "menh").lines()).containsExactly("");
  }

  private static BasicReading.RenderedSection section(BasicReading r, String id) {
    return r.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }
}
