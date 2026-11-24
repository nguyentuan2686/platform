package dev.platform.reading.tuvi.adapter.out.persistence;

import dev.platform.reading.tuvi.application.BasicReading;
import dev.platform.reading.tuvi.application.ReadingCachePort;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code basic_reading_cache}: rendered readings keyed by (chart hash, knowledge version, render
 * version).
 */
@Repository
public class JdbcReadingCache implements ReadingCachePort {

  private static final JsonMapper JSON = JsonMapper.shared();

  private final JdbcTemplate jdbc;

  public JdbcReadingCache(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<BasicReading> find(String chartHash, long knowledgeVersion, int renderVersion) {
    return jdbc
        .queryForList(
            "select body::text from basic_reading_cache"
                + " where chart_hash = ? and knowledge_version = ? and render_version = ?",
            String.class,
            chartHash,
            knowledgeVersion,
            renderVersion)
        .stream()
        .findFirst()
        .map(json -> JSON.readValue(json, BasicReading.class));
  }

  @Override
  public void put(BasicReading reading, int renderVersion) {
    jdbc.update(
        "insert into basic_reading_cache (chart_hash, knowledge_version, render_version, body)"
            + " values (?, ?, ?, ?::jsonb) on conflict do nothing",
        reading.chartHash(),
        reading.knowledgeVersion(),
        renderVersion,
        JSON.writeValueAsString(reading));
  }
}
