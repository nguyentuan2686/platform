package dev.platform.reading.tuvi.adapter.out.persistence;

import dev.platform.reading.tuvi.application.qa.QaAnswerCachePort;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code qa_answer_cache}: encrypted first answers; the first entry for a key wins. */
@Repository
public class JdbcQaAnswerCache implements QaAnswerCachePort {

  private final JdbcTemplate jdbc;

  public JdbcQaAnswerCache(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<byte[]> find(String cacheKey) {
    return jdbc
        .query(
            "select answer_enc from qa_answer_cache where cache_key = ?",
            (rs, n) -> rs.getBytes(1),
            cacheKey)
        .stream()
        .findFirst();
  }

  @Override
  public void put(String cacheKey, byte[] answerEnc) {
    jdbc.update(
        "insert into qa_answer_cache (cache_key, answer_enc) values (?, ?)"
            + " on conflict (cache_key) do nothing",
        cacheKey,
        answerEnc);
  }
}
