package dev.platform.identity;

import static dev.platform.identity.support.HttpIdentity.PROFILE_BODY;
import static org.assertj.core.api.Assertions.assertThat;

import dev.platform.identity.support.HttpIdentity;
import dev.platform.identity.support.HttpIdentity.Login;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A login, a profile write and a consume must leave no email, token or birth data in the logs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@Import(NoPiiInLogsIT.RecordingMailer.class)
class NoPiiInLogsIT {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @TestConfiguration
  static class RecordingMailer {
    @Bean
    @Primary
    dev.platform.identity.application.MailerPort recordingMailer() {
      return (to, link, lang) -> HttpIdentity.LINKS.add(link);
    }
  }

  @LocalServerPort int port;

  @Test
  void login_profile_and_consume_log_no_pii(CapturedOutput output) throws Exception {
    HttpIdentity api = new HttpIdentity(port);
    String local = "pii-" + UUID.randomUUID();
    String email = local + "@example.org";

    Login login = api.login(email);
    String magicToken =
        HttpIdentity.LINKS.get(HttpIdentity.LINKS.size() - 1).getRawQuery().replace("token=", "");
    assertThat(api.send("PUT", "/me/profile", login.token(), PROFILE_BODY).statusCode())
        .isEqualTo(200);
    assertThat(api.consume(login.accountId(), UUID.randomUUID(), "TaiLoc").statusCode())
        .isEqualTo(200);
    // Failure paths log too: a locked-birth conflict and a rejected body must stay clean.
    api.send("PUT", "/me/profile", login.token(), PROFILE_BODY.replace("1994-03-12", "1990-01-01"));
    api.send("PUT", "/me/profile", login.token(), PROFILE_BODY.replace("1994-03-12", "1994-02-30"));

    String logs = output.getAll();
    assertThat(logs).as("the run logged something").contains("identity-svc");
    assertThat(logs)
        .doesNotContain(email)
        .doesNotContain(local)
        .doesNotContain(login.token())
        .doesNotContain(magicToken)
        .doesNotContain("1994-03-12")
        .doesNotContain("1990-01-01")
        .doesNotContain("1994-02-30");
  }
}
