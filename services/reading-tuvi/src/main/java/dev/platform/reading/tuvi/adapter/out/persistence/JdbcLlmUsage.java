package dev.platform.reading.tuvi.adapter.out.persistence;

import dev.platform.reading.tuvi.application.llm.LlmUsagePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code llm_usage} (per provider and UTC day) and {@code llm_provider_state}. */
@Repository
public class JdbcLlmUsage implements LlmUsagePort {

  private final JdbcTemplate jdbc;

  public JdbcLlmUsage(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int requestsOn(String provider, LocalDate utcDay) {
    return jdbc
        .queryForList(
            "select requests from llm_usage where provider = ? and day = ?",
            Integer.class,
            provider,
            utcDay)
        .stream()
        .findFirst()
        .orElse(0);
  }

  @Override
  public void recordAttempt(String provider, LocalDate utcDay) {
    jdbc.update(
        "insert into llm_usage (provider, day, requests) values (?, ?, 1)"
            + " on conflict (provider, day) do update set requests = llm_usage.requests + 1",
        provider,
        utcDay);
  }

  @Override
  public void recordTokens(
      String provider, LocalDate utcDay, int prompt, int completion, long costMicroUsd) {
    jdbc.update(
        "insert into llm_usage (provider, day, prompt_tokens, completion_tokens, cost_micro_usd)"
            + " values (?, ?, ?, ?, ?)"
            + " on conflict (provider, day) do update set"
            + " prompt_tokens = llm_usage.prompt_tokens + excluded.prompt_tokens,"
            + " completion_tokens = llm_usage.completion_tokens + excluded.completion_tokens,"
            + " cost_micro_usd = llm_usage.cost_micro_usd + excluded.cost_micro_usd",
        provider,
        utcDay,
        prompt,
        completion,
        costMicroUsd);
  }

  @Override
  public long spendMicroUsd(String provider, YearMonth utcMonth) {
    Long sum =
        jdbc.queryForObject(
            "select coalesce(sum(cost_micro_usd), 0) from llm_usage"
                + " where provider = ? and day between ? and ?",
            Long.class,
            provider,
            utcMonth.atDay(1),
            utcMonth.atEndOfMonth());
    return sum == null ? 0L : sum;
  }

  @Override
  public void markExhausted(String provider, Instant until) {
    jdbc.update(
        "insert into llm_provider_state (provider, exhausted_until) values (?, ?)"
            + " on conflict (provider) do update set"
            + " exhausted_until = greatest(llm_provider_state.exhausted_until,"
            + " excluded.exhausted_until)",
        provider,
        Timestamp.from(until));
  }

  @Override
  public Optional<Instant> exhaustedUntil(String provider) {
    return jdbc
        .queryForList(
            "select exhausted_until from llm_provider_state where provider = ?",
            Timestamp.class,
            provider)
        .stream()
        .findFirst()
        .map(Timestamp::toInstant);
  }
}
