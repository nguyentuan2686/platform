package dev.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.platform.identity.adapter.out.mail.SmtpMailerAdapter;
import dev.platform.identity.application.MailDeliveryException;
import dev.platform.identity.domain.Email;
import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The prod SMTP settings of application.yml (MAIL_SMTP_AUTH, MAIL_USERNAME/MAIL_PASSWORD,
 * MAIL_STARTTLS, 5 s timeouts) against a GreenMail relay that knows one user: the mail is sent with
 * AUTH, a wrong password is refused, and STARTTLS required refuses a server without it.
 */
@SpringBootTest(
    classes = SmtpAuthIT.Mail.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"MAIL_SMTP_AUTH=true", "MAIL_USERNAME=relay-user"})
class SmtpAuthIT {

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(MailSenderAutoConfiguration.class)
  static class Mail {}

  // Started before the Spring context (a JUnit extension would start it only per test, after the
  // context).
  static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  static {
    SMTP.setUser("relay-user", "local-test-pw");
    SMTP.start();
  }

  @AfterEach
  void purge() throws Exception {
    SMTP.purgeEmailFromAllMailboxes();
  }

  @AfterAll
  static void stop() {
    SMTP.stop();
  }

  @DynamicPropertySource
  static void smtp(DynamicPropertyRegistry registry) {
    registry.add("MAIL_HOST", () -> "127.0.0.1");
    registry.add("MAIL_PORT", () -> SMTP.getSmtp().getPort());
    registry.add("MAIL_PASSWORD", () -> "local-test-pw");
  }

  @Autowired JavaMailSenderImpl sender;

  private static final URI LINK = URI.create("http://localhost:3001/vi/dang-nhap/xac-nhan?token=t");

  @Test
  void sends_with_auth_to_the_relay() throws Exception {
    new SmtpMailerAdapter(sender, "no-reply@app.example.invalid")
        .sendMagicLink(new Email("a@example.com"), LINK, "vi");

    assertThat(SMTP.getReceivedMessages()).hasSize(1);
    assertThat(SMTP.getReceivedMessages()[0].getAllRecipients()[0].toString())
        .isEqualTo("a@example.com");
  }

  @Test
  void wrong_password_is_refused() {
    JavaMailSenderImpl wrong = copyOf(sender);
    wrong.setPassword("not-the-password");

    assertThatThrownBy(
            () ->
                new SmtpMailerAdapter(wrong, "no-reply@app.example.invalid")
                    .sendMagicLink(new Email("a@example.com"), LINK, "vi"))
        .isInstanceOf(MailDeliveryException.class);
  }

  @Test
  void starttls_required_refuses_a_server_without_tls() {
    JavaMailSenderImpl tls = copyOf(sender);
    tls.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
    tls.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "true");

    assertThatThrownBy(
            () ->
                new SmtpMailerAdapter(tls, "no-reply@app.example.invalid")
                    .sendMagicLink(new Email("a@example.com"), LINK, "vi"))
        .isInstanceOf(MailDeliveryException.class);
  }

  @Test
  void connect_read_and_write_time_out_after_5_seconds() {
    Properties props = sender.getJavaMailProperties();
    assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("5000");
    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
  }

  private static JavaMailSenderImpl copyOf(JavaMailSenderImpl s) {
    JavaMailSenderImpl c = new JavaMailSenderImpl();
    c.setHost(s.getHost());
    c.setPort(s.getPort());
    c.setUsername(s.getUsername());
    c.setPassword(s.getPassword());
    Properties p = new Properties();
    p.putAll(s.getJavaMailProperties());
    c.setJavaMailProperties(p);
    return c;
  }
}
