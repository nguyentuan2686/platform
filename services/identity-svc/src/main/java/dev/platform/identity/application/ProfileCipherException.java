package dev.platform.identity.application;

/** Birth data could not be sealed or opened. The message carries no data. */
public class ProfileCipherException extends RuntimeException {

  public ProfileCipherException(String message, Throwable cause) {
    super(message, cause);
  }
}
