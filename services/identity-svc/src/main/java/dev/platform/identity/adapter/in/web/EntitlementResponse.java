package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.EntitlementView;
import dev.platform.identity.domain.PassKind;
import java.time.Instant;
import java.util.List;

public record EntitlementResponse(int credits, PassBody pass, List<String> features) {

  public record PassBody(
      PassKind kind,
      @JsonProperty("ends_at") Instant endsAt,
      @JsonProperty("questions_left") Integer questionsLeft) {}

  static EntitlementResponse of(EntitlementView view) {
    PassBody pass =
        view.pass() == null
            ? null
            : new PassBody(view.pass().kind(), view.pass().endsAt(), view.pass().questionsLeft());
    return new EntitlementResponse(view.credits(), pass, view.features());
  }
}
