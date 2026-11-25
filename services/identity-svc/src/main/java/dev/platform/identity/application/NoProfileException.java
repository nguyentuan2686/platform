package dev.platform.identity.application;

/** The caller has no chart profile to act on (409 reason no_profile). */
public class NoProfileException extends RuntimeException {

  public NoProfileException() {
    super("no_profile");
  }
}
