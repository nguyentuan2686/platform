package dev.platform.reading.tuvi.application.qa;

/** No bearer, or identity-svc does not know the session. */
public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException() {
    super("Authentication required");
  }
}
