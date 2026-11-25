package dev.platform.identity.application;

/** The caller is authenticated but not allowed (a guest asking for account-only data). */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException() {
    super("Forbidden");
  }
}
