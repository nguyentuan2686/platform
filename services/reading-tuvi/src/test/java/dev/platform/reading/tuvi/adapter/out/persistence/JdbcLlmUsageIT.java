package dev.platform.reading.tuvi.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JdbcLlmUsageIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final LocalDate DAY = LocalDate.of(2026, 11, 25);
  static JdbcTemplate jdbc;

  JdbcLlmUsage usage;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from llm_usage");
    jdbc.update("delete from llm_provider_state");
    usage = new JdbcLlmUsage(jdbc);
  }

  @Test
  void two_attempts_count_two() {
    usage.recordAttempt("groq", DAY);
    usage.recordAttempt("groq", DAY);

    assertThat(usage.requestsOn("groq", DAY)).isEqualTo(2);
    assertThat(usage.requestsOn("groq", DAY.plusDays(1))).isZero();
    assertThat(usage.requestsOn("gemini", DAY)).isZero();
  }

  @Test
  void tokens_and_cost_accumulate_per_month() {
    usage.recordTokens("paid", DAY, 100, 50, 590L);
    usage.recordTokens("paid", DAY.plusDays(1), 10, 5, 10L);
    usage.recordTokens("paid", LocalDate.of(2026, 12, 1), 1, 1, 999L);

    assertThat(usage.spendMicroUsd("paid", YearMonth.of(2026, 11))).isEqualTo(600L);
    assertThat(usage.spendMicroUsd("paid", YearMonth.of(2026, 12))).isEqualTo(999L);
    assertThat(usage.spendMicroUsd("paid", YearMonth.of(2026, 10))).isZero();
  }

  @Test
  void mark_exhausted_keeps_the_later_instant() {
    var early = Instant.parse("2026-11-25T10:00:00Z");
    var late = Instant.parse("2026-11-26T00:00:00Z");

    assertThat(usage.exhaustedUntil("groq")).isEmpty();
    usage.markExhausted("groq", late);
    usage.markExhausted("groq", early);

    assertThat(usage.exhaustedUntil("groq")).contains(late);
  }
}
