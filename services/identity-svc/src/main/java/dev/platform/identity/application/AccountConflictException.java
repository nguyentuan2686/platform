package dev.platform.identity.application;

/** A concurrent request took the unique value (e.g. a Google subject) first; retry the lookup. */
public class AccountConflictException extends RuntimeException {

  public AccountConflictException() {
    super("account was changed concurrently", null, false, false);
  }
}
