package dev.platform.reading.tuvi.application.qa;

/** A guest session asked for something only an account can do (a paid question). */
public class AccountRequiredException extends RuntimeException {

  public AccountRequiredException() {
    super("Account required");
  }
}
