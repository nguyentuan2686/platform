package dev.platform.payment.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.RefundUseCase;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Customer refund of an unused credit pack (internal API; the web BFF supplies account_id). */
@RestController
public class RefundController {

  public record RefundBody(@JsonProperty("account_id") UUID accountId) {}

  private final RefundUseCase refunds;

  public RefundController(RefundUseCase refunds) {
    this.refunds = refunds;
  }

  /** 201 for a new request, 200 with the existing one on a repeat. */
  @PostMapping("/orders/{id}/refund-requests")
  ResponseEntity<AdminController.RefundRequestResponse> request(
      @PathVariable UUID id, @RequestBody RefundBody body) {
    if (body == null || body.accountId() == null) {
      throw new OrderValidationException("account_id", "is required");
    }
    RefundUseCase.Result result = refunds.request(id, body.accountId());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(AdminController.RefundRequestResponse.from(result.request()));
  }
}
