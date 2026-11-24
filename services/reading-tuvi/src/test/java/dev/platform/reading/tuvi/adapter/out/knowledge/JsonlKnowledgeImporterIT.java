package dev.platform.reading.tuvi.adapter.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.platform.reading.tuvi.application.KnowledgePort;
import dev.platform.reading.tuvi.domain.KnowledgeKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
class JsonlKnowledgeImporterIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  static final Path SEED = Path.of("..", "..", "knowledge", "tuvi");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JsonlKnowledgeImporter importer;
  @Autowired KnowledgePort port;
  @Autowired JdbcTemplate jdbc;

  @TempDir Path tmp;

  @BeforeEach
  void clean() {
    jdbc.update("delete from knowledge");
    jdbc.update("update knowledge_meta set version = 0 where id = 1");
  }

  private long count() {
    return jdbc.queryForObject("select count(*) from knowledge", Long.class);
  }

  private static String line(String key, String content) {
    return "{\"key\":\""
        + key
        + "\",\"school\":\"iztro-default\",\"lang\":\"vi\",\"content\":\""
        + content
        + "\",\"source_ref\":\"original:frame\",\"status\":\"approved\"}";
  }

  private Path write(String name, String... lines) throws IOException {
    return Files.writeString(tmp.resolve(name), String.join("\n", lines) + "\n");
  }

  @Test
  void first_import_inserts_seed_and_second_is_a_noop() {
    ImportResult first = importer.importDir(SEED);
    assertThat(first).isEqualTo(new ImportResult(24, 0, 0));
    assertThat(port.version()).isEqualTo(1);

    ImportResult second = importer.importDir(SEED);
    assertThat(second).isEqualTo(new ImportResult(0, 0, 24));
    assertThat(port.version()).isEqualTo(1);
  }

  @Test
  void find_returns_approved_content_only() throws IOException {
    write(
        "a.jsonl",
        line("tuvi:frame=missing", "chưa có"),
        line("tuvi:frame=closing", "hẹn gặp").replace("approved", "draft"));
    importer.importDir(tmp);

    assertThat(port.find(KnowledgeKey.frame("missing"), "vi")).contains("chưa có");
    assertThat(port.find(KnowledgeKey.frame("closing"), "vi")).isEmpty();
    assertThat(port.find(KnowledgeKey.frame("greeting"), "vi")).isEmpty();
  }

  @Test
  void changed_content_bumps_record_and_meta_version() throws IOException {
    write("a.jsonl", line("tuvi:frame=missing", "một"), line("tuvi:frame=closing", "hai"));
    importer.importDir(tmp);
    write("a.jsonl", line("tuvi:frame=missing", "một khác"), line("tuvi:frame=closing", "hai"));

    ImportResult r = importer.importDir(tmp);

    assertThat(r).isEqualTo(new ImportResult(0, 1, 1));
    assertThat(
            jdbc.queryForObject(
                "select version from knowledge where key = 'tuvi:frame=missing'", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select version from knowledge where key = 'tuvi:frame=closing'", Integer.class))
        .isEqualTo(1);
    assertThat(port.version()).isEqualTo(2);
  }

  @Test
  void status_change_without_content_change_is_an_update() throws IOException {
    write("a.jsonl", line("tuvi:frame=missing", "một").replace("approved", "draft"));
    importer.importDir(tmp);
    write("a.jsonl", line("tuvi:frame=missing", "một"));

    assertThat(importer.importDir(tmp)).isEqualTo(new ImportResult(0, 1, 0));
    assertThat(port.find(KnowledgeKey.frame("missing"), "vi")).contains("một");
  }

  @Test
  void invalid_key_on_line_3_rolls_everything_back() throws IOException {
    write(
        "bad.jsonl",
        line("tuvi:frame=missing", "một"),
        line("tuvi:frame=closing", "hai"),
        line("tuvi:Star=X", "ba"));

    assertThatThrownBy(() -> importer.importDir(tmp))
        .isInstanceOf(KnowledgeImportException.class)
        .hasMessageContaining("bad.jsonl:3");
    assertThat(count()).isZero();
    assertThat(port.version()).isZero();
  }

  @Test
  void invalid_records_are_rejected_with_file_and_line() throws IOException {
    String ok = line("tuvi:frame=missing", "một");
    assertRejected(ok, line("tuvi:frame=closing", ""), "bad.jsonl:2");
    assertRejected(ok, line("tuvi:frame=closing", "x").replace("\"vi\"", "\"en\""), "bad.jsonl:2");
    assertRejected(ok, line("tuvi:frame=missing", "trùng"), "bad.jsonl:2");
    assertRejected(ok, "{not json", "bad.jsonl:2");
    assertRejected(ok, line("tuvi:frame=closing", "x").replace("approved", "bogus"), "bad.jsonl:2");
    assertThat(count()).isZero();
  }

  private void assertRejected(String first, String second, String where) throws IOException {
    write("bad.jsonl", first, second);
    assertThatThrownBy(() -> importer.importDir(tmp))
        .isInstanceOf(KnowledgeImportException.class)
        .hasMessageContaining(where);
  }
}
