package dev.platform.identity.domain;

/** The birth data is locked for this account and the request tried to change it. */
public class ProfileLockedException extends RuntimeException {

  public ProfileLockedException() {
    super("Birth data is locked");
  }
}
