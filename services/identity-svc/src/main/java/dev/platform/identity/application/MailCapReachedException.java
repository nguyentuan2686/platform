package dev.platform.identity.application;

/** The global daily cap on magic-link mail is reached: nothing is sent or recorded. */
public class MailCapReachedException extends RuntimeException {

  public MailCapReachedException() {
    super("magic link daily mail cap reached");
  }
}
