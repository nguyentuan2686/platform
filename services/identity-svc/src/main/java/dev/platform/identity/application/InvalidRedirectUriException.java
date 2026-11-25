package dev.platform.identity.application;

/** The redirect URI is missing or not in the allow-list. */
public class InvalidRedirectUriException extends RuntimeException {

  public InvalidRedirectUriException() {
    super("redirect_uri is not allowed", null, false, false);
  }
}
