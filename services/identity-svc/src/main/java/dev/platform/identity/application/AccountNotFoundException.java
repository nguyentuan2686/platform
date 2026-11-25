package dev.platform.identity.application;

/** No such account. */
public class AccountNotFoundException extends RuntimeException {

  public AccountNotFoundException() {
    super("Account not found");
  }
}
