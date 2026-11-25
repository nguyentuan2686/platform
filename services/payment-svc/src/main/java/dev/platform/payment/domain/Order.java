package dev.platform.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A purchase of one product by one account, paid through one provider. */
public final class Order {

  private final UUID id;
  private final OrderCode code;
  private final UUID accountId;
  private final String site;
  private final String productCode;
  private final UUID chartProfileId;
  private final Money amount;
  private final Provider provider;
  private final Instant createdAt;
  private final Instant expiresAt;
  private OrderStatus status;
  private String providerRef;
  private String note;
  private Instant paidAt;

  private Order(
      UUID id,
      OrderCode code,
      UUID accountId,
      String site,
      String productCode,
      UUID chartProfileId,
      Money amount,
      Provider provider,
      OrderStatus status,
      String providerRef,
      String note,
      Instant createdAt,
      Instant expiresAt,
      Instant paidAt) {
    this.id = id;
    this.code = code;
    this.accountId = accountId;
    this.site = site;
    this.productCode = productCode;
    this.chartProfileId = chartProfileId;
    this.amount = amount;
    this.provider = provider;
    this.status = status;
    this.providerRef = providerRef;
    this.note = note;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.paidAt = paidAt;
  }

  public static Order create(
      UUID id,
      OrderCode code,
      UUID accountId,
      String site,
      String productCode,
      UUID chartProfileId,
      Money amount,
      Provider provider,
      Instant now,
      Duration paymentWindow) {
    return new Order(
        id,
        code,
        accountId,
        site,
        productCode,
        chartProfileId,
        amount,
        provider,
        OrderStatus.PENDING,
        null,
        null,
        now,
        now.plus(paymentWindow),
        null);
  }

  public static Order restore(
      UUID id,
      OrderCode code,
      UUID accountId,
      String site,
      String productCode,
      UUID chartProfileId,
      Money amount,
      Provider provider,
      OrderStatus status,
      String providerRef,
      String note,
      Instant createdAt,
      Instant expiresAt,
      Instant paidAt) {
    return new Order(
        id,
        code,
        accountId,
        site,
        productCode,
        chartProfileId,
        amount,
        provider,
        status,
        providerRef,
        note,
        createdAt,
        expiresAt,
        paidAt);
  }

  /** The provider's own order id (PayPal), known once the checkout exists. */
  public void attachProviderRef(String ref) {
    this.providerRef = ref;
  }

  /**
   * Marks the order paid. Overpayment is recorded in the note; underpayment or another currency is
   * rejected and the order stays pending; a non-pending order throws {@link IllegalStateException}.
   * An existing provider reference (the PayPal order id) is kept, otherwise the given one (the bank
   * transaction id) is stored.
   */
  public PaymentSucceeded markPaid(Money received, String ref, Instant at) {
    if (status != OrderStatus.PENDING) {
      throw new IllegalStateException("order is not pending");
    }
    if (received.isLessThan(amount)) {
      throw new IllegalArgumentException("received amount is below the price");
    }
    if (received.isGreaterThan(amount)) {
      note = "overpaid_by=" + received.minus(amount).toPlainString();
    }
    status = OrderStatus.PAID;
    paidAt = at;
    if (providerRef == null) {
      providerRef = ref;
    }
    return new PaymentSucceeded(
        id, code.value(), accountId, productCode, site, amount, provider, at, chartProfileId);
  }

  /** A paid order whose customer asked for the money back. */
  public void requestRefund() {
    if (status != OrderStatus.PAID) {
      throw new IllegalStateException("order is not paid");
    }
    status = OrderStatus.REFUND_REQUESTED;
  }

  /** The money went back to the customer. */
  public void completeRefund() {
    if (status != OrderStatus.REFUND_REQUESTED) {
      throw new IllegalStateException("no refund was requested");
    }
    status = OrderStatus.REFUNDED;
  }

  /** Status to show: a pending order past its window reads EXPIRED (late payments still match). */
  public String viewStatus(Instant now) {
    if (status == OrderStatus.PENDING && now.isAfter(expiresAt)) {
      return "EXPIRED";
    }
    return status.name();
  }

  public UUID id() {
    return id;
  }

  public OrderCode code() {
    return code;
  }

  public UUID accountId() {
    return accountId;
  }

  public String site() {
    return site;
  }

  public String productCode() {
    return productCode;
  }

  public UUID chartProfileId() {
    return chartProfileId;
  }

  public Money amount() {
    return amount;
  }

  public Provider provider() {
    return provider;
  }

  public OrderStatus status() {
    return status;
  }

  public String providerRef() {
    return providerRef;
  }

  public String note() {
    return note;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant paidAt() {
    return paidAt;
  }
}
