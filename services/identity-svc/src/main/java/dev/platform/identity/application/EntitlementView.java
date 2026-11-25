package dev.platform.identity.application;

import dev.platform.identity.domain.PassKind;
import java.time.Instant;
import java.util.List;

/** {@code pass} is null without an active pass; {@code questionsLeft} null means unlimited. */
public record EntitlementView(int credits, PassView pass, List<String> features) {

  public record PassView(PassKind kind, Instant endsAt, Integer questionsLeft) {}
}
