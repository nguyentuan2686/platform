package dev.platform.identity.adapter.out.mail;

import dev.platform.identity.application.MailerPort;
import dev.platform.identity.domain.Email;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends the login link as a plain-text Vietnamese mail. Never logs the address or the link. */
public class SmtpMailerAdapter implements MailerPort {

  private static final Logger log = LoggerFactory.getLogger(SmtpMailerAdapter.class);

  static final String SUBJECT = "Đăng nhập xem tử vi";

  private final JavaMailSender sender;
  private final String from;

  public SmtpMailerAdapter(JavaMailSender sender, String from) {
    this.sender = sender;
    this.from = from;
  }

  @Override
  public void sendMagicLink(Email to, URI link, String lang) {
    String body =
        "Nhấn vào liên kết để đăng nhập: "
            + link
            + "\n\nLưu ý: liên kết dùng một lần, hết hạn sau 15 phút.\n";
    try {
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
      helper.setFrom(from);
      helper.setTo(to.value());
      helper.setSubject(SUBJECT);
      helper.setText(body, false);
      sender.send(message);
    } catch (MessagingException e) {
      throw new IllegalStateException("could not build the magic link mail", e);
    }
    log.info("magic link mail sent");
  }
}
