package dev.platform.identity.application;

/** The caller has no chart profile yet. */
public class ProfileNotFoundException extends RuntimeException {

  public ProfileNotFoundException() {
    super("Profile not found");
  }
}
