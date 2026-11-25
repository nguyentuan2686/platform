package dev.platform.reading.tuvi.application.qa;

import java.util.UUID;

/** Who a session belongs to: {@code kind} is {@code guest} or {@code account}. */
public record IdentitySubject(String kind, UUID id) {

  public boolean guest() {
    return "guest".equals(kind);
  }
}
