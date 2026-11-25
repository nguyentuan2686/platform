package dev.platform.identity.application;

/** The caller presented no usable credential (missing, unknown, expired, revoked or used). */
public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException() {
    super("Authentication required");
  }
}
