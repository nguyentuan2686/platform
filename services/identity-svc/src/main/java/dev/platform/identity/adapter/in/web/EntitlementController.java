package dev.platform.identity.adapter.in.web;

import dev.platform.identity.application.EntitlementUseCase;
import dev.platform.identity.application.SessionUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The caller's credits, pass and ledger (account bearer only; a guest gets 403). */
@RestController
public class EntitlementController {

  private final EntitlementUseCase entitlements;
  private final SessionUseCase sessions;

  public EntitlementController(EntitlementUseCase entitlements, SessionUseCase sessions) {
    this.entitlements = entitlements;
    this.sessions = sessions;
  }

  @GetMapping("/me/entitlements")
  EntitlementResponse entitlements(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return EntitlementResponse.of(
        entitlements.view(sessions.resolve(Bearer.require(authorization))));
  }

  @GetMapping("/me/ledger")
  List<LedgerEntryResponse> ledger(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return entitlements.ledger(sessions.resolve(Bearer.require(authorization))).stream()
        .map(LedgerEntryResponse::of)
        .toList();
  }
}
