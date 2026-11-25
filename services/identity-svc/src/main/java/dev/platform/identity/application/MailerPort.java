package dev.platform.identity.application;

import dev.platform.identity.domain.Email;
import java.net.URI;

public interface MailerPort {

  void sendMagicLink(Email to, URI link, String lang);
}
