package dev.platform.reading.tuvi.adapter.out.knowledge;

import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeRepository implements KnowledgePort {

  /** Knowledge school: the chart school family up to the first '+' or ';'. */
  static final String SCHOOL = "iztro-default";

  private final JdbcTemplate jdbc;

  public JdbcKnowledgeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> find(KnowledgeKey key, String lang) {
    return jdbc
        .queryForList(
            "select content from knowledge"
                + " where key = ? and school = ? and lang = ? and status = 'approved'",
            String.class,
            key.value(),
            SCHOOL,
            lang)
        .stream()
        .findFirst();
  }

  @Override
  public long version() {
    return jdbc.queryForObject("select version from knowledge_meta where id = 1", Long.class);
  }
}
