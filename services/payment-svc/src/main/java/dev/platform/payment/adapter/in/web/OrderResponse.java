package dev.platform.payment.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.platform.payment.application.CreatedOrder;
import dev.platform.payment.application.OrderView;
import dev.platform.payment.application.PaymentInstructions;
import dev.platform.payment.domain.Order;
import java.time.Instant;
import java.util.UUID;

/** An order as returned by the API; {@code payment} is present only on creation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    @JsonProperty("order_id") UUID orderId,
    String code,
    String status,
    String amount,
    String currency,
    String provider,
    @JsonProperty("product_code") String productCode,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("expires_at") Instant expiresAt,
    @JsonProperty("paid_at") Instant paidAt,
    PaymentResponse payment) {

  /** Either a VIETQR or a PAYPAL payload; the fields not of that kind are omitted. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PaymentResponse(
      String kind,
      @JsonProperty("bank_code") String bankCode,
      @JsonProperty("account_number") String accountNumber,
      @JsonProperty("account_name") String accountName,
      String amount,
      String content,
      @JsonProperty("qr_image_url") String qrImageUrl,
      @JsonProperty("approve_url") String approveUrl) {

    static PaymentResponse from(PaymentInstructions payment) {
      return switch (payment) {
        case PaymentInstructions.VietQr q ->
            new PaymentResponse(
                "VIETQR",
                q.bankCode(),
                q.accountNumber(),
                q.accountName(),
                q.amount().toPlainString(),
                q.content(),
                q.qrImageUrl(),
                null);
        case PaymentInstructions.PayPal p ->
            new PaymentResponse(
                "PAYPAL", null, null, null, null, null, null, p.approveUrl().toString());
      };
    }

    @Override
    public String toString() {
      return "PaymentResponse[redacted]";
    }
  }

  static OrderResponse from(CreatedOrder created, String status) {
    return of(created.order(), status, PaymentResponse.from(created.payment()));
  }

  static OrderResponse from(OrderView view) {
    return of(view.order(), view.status(), null);
  }

  private static OrderResponse of(Order order, String status, PaymentResponse payment) {
    return new OrderResponse(
        order.id(),
        order.code().value(),
        status,
        order.amount().toPlainString(),
        order.amount().currency(),
        order.provider().name(),
        order.productCode(),
        order.createdAt(),
        order.expiresAt(),
        order.paidAt(),
        payment);
  }
}
