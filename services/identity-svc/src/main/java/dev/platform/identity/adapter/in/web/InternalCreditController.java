package dev.platform.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.identity.application.ConsumeResult;
import dev.platform.identity.application.EntitlementUseCase;
import dev.platform.identity.domain.CreditSource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service credit operations (guarded by {@code X-Internal-Token}). */
@RestController
public class InternalCreditController {

  private final EntitlementUseCase entitlements;

  public InternalCreditController(EntitlementUseCase entitlements) {
    this.entitlements = entitlements;
  }

  public record ConsumeResponse(
      CreditSource source, @JsonProperty("credits_left") int creditsLeft) {}

  public record RefundResponse(boolean refunded) {}

  public record OkResponse(boolean ok) {}

  @PostMapping("/internal/credits/consume")
  ConsumeResponse consume(@Valid @RequestBody ConsumeRequest request) {
    ConsumeResult result =
        entitlements.consume(request.accountId(), request.readingId(), request.topic());
    return new ConsumeResponse(result.source(), result.creditsLeft());
  }

  @PostMapping("/internal/credits/refund")
  RefundResponse refund(@Valid @RequestBody RefundRequest request) {
    entitlements.refund(request.readingId());
    return new RefundResponse(true);
  }

  @PostMapping("/internal/credits/reversals")
  OkResponse reverse(@Valid @RequestBody ReversalRequest request) {
    entitlements.reverse(request.accountId(), request.orderId(), request.credits());
    return new OkResponse(true);
  }
}
