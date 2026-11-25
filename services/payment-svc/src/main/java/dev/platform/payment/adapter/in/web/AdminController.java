package dev.platform.payment.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.payment.application.AdminUseCase;
import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The operator API; every path here is guarded by {@link AdminTokenFilter}. */
@RestController
public class AdminController {

  /** A queue row; {@code content} is the payer's text, shown to the operator only. */
  public record UnmatchedResponse(
      long id,
      String provider,
      String amount,
      String currency,
      @JsonProperty("occurred_at") Instant occurredAt,
      @JsonProperty("unmatched_reason") String unmatchedReason,
      @JsonProperty("candidate_codes") List<String> candidateCodes,
      String content) {

    @Override
    public String toString() {
      return "UnmatchedResponse[" + id + ", content=[redacted]]";
    }
  }

  public record AssignRequest(@JsonProperty("order_code") String orderCode) {}

  public record ResolveRequest(String resolution, String note) {}

  public record CompleteRequest(String note) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RefundRequestResponse(
      UUID id,
      @JsonProperty("order_id") UUID orderId,
      String provider,
      String amount,
      String currency,
      String status,
      @JsonProperty("provider_refund_id") String providerRefundId,
      String note,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("completed_at") Instant completedAt) {

    static RefundRequestResponse from(RefundRequest r) {
      return new RefundRequestResponse(
          r.id(),
          r.orderId(),
          r.provider().name(),
          r.amount().toPlainString(),
          r.amount().currency(),
          r.status().name(),
          r.providerRefundId(),
          r.note(),
          r.createdAt(),
          r.completedAt());
    }
  }

  private final AdminUseCase admin;

  public AdminController(AdminUseCase admin) {
    this.admin = admin;
  }

  @GetMapping("/admin/unmatched-transactions")
  List<UnmatchedResponse> unmatched() {
    return admin.unmatched().stream()
        .map(
            u ->
                new UnmatchedResponse(
                    u.id(),
                    u.provider().name(),
                    u.amount().toPlainString(),
                    u.amount().currency(),
                    u.occurredAt(),
                    u.reason() == null ? null : u.reason().name(),
                    u.candidateCodes(),
                    u.content()))
        .toList();
  }

  @PostMapping("/admin/unmatched-transactions/{id}/assign")
  Map<String, Boolean> assign(@PathVariable long id, @RequestBody AssignRequest request) {
    if (request == null || request.orderCode() == null || request.orderCode().isBlank()) {
      throw new OrderValidationException("order_code", "is required");
    }
    admin.assign(id, request.orderCode());
    return Map.of("ok", true);
  }

  @PostMapping("/admin/unmatched-transactions/{id}/resolve")
  Map<String, Boolean> resolve(@PathVariable long id, @RequestBody ResolveRequest request) {
    admin.resolve(id, request.resolution(), request.note());
    return Map.of("ok", true);
  }

  @GetMapping("/admin/refund-requests")
  List<RefundRequestResponse> refundRequests(
      @RequestParam(value = "status", defaultValue = "OPEN") String status) {
    RefundStatus parsed;
    try {
      parsed = RefundStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new OrderValidationException("status", "must be OPEN or DONE");
    }
    return admin.refundRequests(parsed).stream().map(RefundRequestResponse::from).toList();
  }

  @PostMapping("/admin/refund-requests/{id}/complete")
  RefundRequestResponse complete(
      @PathVariable UUID id, @RequestBody(required = false) CompleteRequest request) {
    return RefundRequestResponse.from(
        admin.completeRefund(id, request == null ? null : request.note()));
  }
}
