package dev.platform.identity.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.platform.identity.application.MailDeliveryException;
import dev.platform.identity.domain.Email;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpMailerAdapterTest {

  @Test
  void sends_a_vietnamese_one_line_mail_with_the_link() throws Exception {
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
    var adapter = new SmtpMailerAdapter(sender, "no-reply@localhost");
    URI link = URI.create("http://localhost:3001/vi/dang-nhap/xac-nhan?token=abc");

    adapter.sendMagicLink(new Email("a@example.com"), link, "vi");

    ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
    verify(sender).send(sent.capture());
    MimeMessage message = sent.getValue();
    assertThat(message.getSubject()).isEqualTo("Đăng nhập xem tử vi");
    assertThat(message.getAllRecipients()[0].toString()).isEqualTo("a@example.com");
    assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@localhost");
    String body = (String) message.getContent();
    assertThat(body)
        .contains(link.toString())
        .contains("liên kết dùng một lần, hết hạn sau 15 phút");
  }

  @Test
  void send_failure_is_redacted_and_carries_no_address_or_cause() {
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
    doThrow(new MailSendException("Invalid addresses: a@example.com"))
        .when(sender)
        .send(any(MimeMessage.class));
    var adapter = new SmtpMailerAdapter(sender, "no-reply@localhost");
    Logger logger = (Logger) LoggerFactory.getLogger(SmtpMailerAdapter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThatThrownBy(
              () ->
                  adapter.sendMagicLink(
                      new Email("a@example.com"),
                      URI.create("http://localhost/x?token=secret"),
                      "vi"))
          .isInstanceOf(MailDeliveryException.class)
          .hasNoCause()
          .hasMessageNotContaining("example.com");
      for (ILoggingEvent e : logs.list) {
        assertThat(e.getFormattedMessage() + e.getThrowableProxy())
            .doesNotContain("example.com", "secret");
      }
    } finally {
      logger.detachAppender(logs);
    }
  }
}
