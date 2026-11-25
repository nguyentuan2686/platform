package dev.platform.identity.application;

/** Google login is not configured or the provider could not be reached. */
public class OidcUnavailableException extends RuntimeException {

  private final boolean notConfigured;

  private OidcUnavailableException(String message, boolean notConfigured, Throwable cause) {
    super(message, cause, false, false);
    this.notConfigured = notConfigured;
  }

  public static OidcUnavailableException notConfigured() {
    return new OidcUnavailableException("Google login is not configured", true, null);
  }

  public static OidcUnavailableException unreachable(Throwable cause) {
    return new OidcUnavailableException("Google login is unavailable", false, cause);
  }

  public boolean isNotConfigured() {
    return notConfigured;
  }
}
