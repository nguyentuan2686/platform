package dev.platform.identity.application;

/** The mail could not be handed to the mail server. Carries no address and no cause. */
public class MailDeliveryException extends RuntimeException {

  public MailDeliveryException() {
    super("Mail delivery failed");
  }
}
