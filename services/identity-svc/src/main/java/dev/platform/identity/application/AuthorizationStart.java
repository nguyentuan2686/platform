package dev.platform.identity.application;

import java.net.URI;

public record AuthorizationStart(URI authorizationUrl, String state) {

  @Override
  public String toString() {
    return "AuthorizationStart[redacted]";
  }
}
