package dev.platform.reading.tuvi.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.number.NumberQuota;
import dev.platform.reading.tuvi.application.number.NumberQuotaExceededException;
import dev.platform.reading.tuvi.application.qa.IdentitySubject;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JdbcNumberUsageIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  static final LocalDate DAY = LocalDate.of(2026, 11, 28);
  static final byte[] IP = bytes("ip-a");
  static JdbcTemplate jdbc;
  static DriverManagerDataSource ds;

  JdbcNumberUsage usage;
  IdentitySubject subject;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from number_check_usage");
    usage = new JdbcNumberUsage(jdbc, new DataSourceTransactionManager(ds));
    subject = new IdentitySubject("guest", UUID.randomUUID());
  }

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  NumberQuota record(IdentitySubject s, LocalDate day, String check, byte[] ip) {
    return usage.recordOrReuse(s, day, bytes(check), ip, 2, 20);
  }

  @Test
  void two_distinct_then_409_and_a_repeat_is_free() {
    assertThat(record(subject, DAY, "n1", IP)).isEqualTo(new NumberQuota(2, 1, 1));
    assertThat(record(subject, DAY, "n2", IP)).isEqualTo(new NumberQuota(2, 2, 0));
    assertThatThrownBy(() -> record(subject, DAY, "n3", IP))
        .isInstanceOf(NumberQuotaExceededException.class)
        .extracting(e -> ((NumberQuotaExceededException) e).quota())
        .isEqualTo(new NumberQuota(2, 2, 0));
    assertThat(record(subject, DAY, "n1", IP)).isEqualTo(new NumberQuota(2, 2, 0));
    assertThat(usage.quota(subject, DAY, 2)).isEqualTo(new NumberQuota(2, 2, 0));
    assertThat(jdbc.queryForObject("select count(*) from number_check_usage", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void a_new_vn_day_resets() {
    record(subject, DAY, "n1", IP);
    record(subject, DAY, "n2", IP);
    assertThat(record(subject, DAY.plusDays(1), "n3", IP)).isEqualTo(new NumberQuota(2, 1, 1));
    assertThat(usage.quota(subject, DAY.plusDays(1), 2).remaining()).isEqualTo(1);
  }

  @Test
  void the_ip_limit_counts_distinct_numbers_across_subjects() {
    IdentitySubject other = new IdentitySubject("account", UUID.randomUUID());
    usage.recordOrReuse(subject, DAY, bytes("n1"), IP, 2, 2);
    usage.recordOrReuse(other, DAY, bytes("n1"), IP, 2, 2);
    usage.recordOrReuse(other, DAY, bytes("n2"), IP, 2, 2);
    IdentitySubject third = new IdentitySubject("guest", UUID.randomUUID());
    assertThatThrownBy(() -> usage.recordOrReuse(third, DAY, bytes("n3"), IP, 2, 2))
        .isInstanceOf(NumberQuotaExceededException.class);
    // a number already seen on that IP by the same subject is still free; without an IP no limit
    assertThat(usage.recordOrReuse(other, DAY, bytes("n2"), IP, 2, 2).used()).isEqualTo(2);
    assertThat(usage.recordOrReuse(third, DAY, bytes("n3"), null, 2, 2).used()).isEqualTo(1);
  }

  @Test
  void two_concurrent_new_numbers_with_one_left_let_exactly_one_through() throws Exception {
    record(subject, DAY, "n1", IP);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (String n : List.of("a", "b")) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    record(subject, DAY, n, IP);
                    return true;
                  } catch (NumberQuotaExceededException e) {
                    return false;
                  }
                }));
      }
      go.countDown();
      int ok = 0;
      for (Future<Boolean> f : results) {
        ok += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(ok).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(usage.quota(subject, DAY, 2).used()).isEqualTo(2);
  }

  @Test
  void purge_deletes_rows_before_the_given_day() {
    record(subject, DAY.minusDays(2), "old", IP);
    record(subject, DAY.minusDays(1), "yesterday", IP);
    record(subject, DAY, "today", IP);
    assertThat(usage.purgeBefore(DAY.minusDays(1))).isEqualTo(1);
    assertThat(jdbc.queryForList("select vn_day from number_check_usage", LocalDate.class))
        .containsExactlyInAnyOrder(DAY.minusDays(1), DAY);
  }

  @Test
  void only_hashes_are_stored() {
    record(subject, DAY, "n1", IP);
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name = 'number_check_usage'",
                String.class))
        .containsExactlyInAnyOrder(
            "subject_kind", "subject_id", "vn_day", "check_hash", "ip_hash", "created_at");
  }
}
