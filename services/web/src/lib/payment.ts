import "server-only";
import { createHmac } from "node:crypto";
import { cfg } from "@/lib/config";
import { callJson } from "@/lib/http";

// Types mirror contracts/openapi/payment-svc.yaml. Money is a decimal string ("119000.00").
export type ProductCode = "CREDIT_1" | "CREDIT_5" | "PASS_MONTH" | "PASS_YEAR";
export type Product = { code: ProductCode; amount: string; currency: "VND" | "USD" };
export type OrderStatus = "PENDING" | "EXPIRED" | "PAID" | "REFUND_REQUESTED" | "REFUNDED";
export type Order = {
  order_id: string;
  code: string;
  status: OrderStatus;
  amount: string;
  currency: "VND" | "USD";
  provider: "SEPAY" | "PAYPAL";
  product_code: ProductCode;
  created_at: string;
  expires_at: string;
  paid_at?: string;
};
export type VietQrPayment = {
  kind: "VIETQR";
  bank_code: string;
  account_number: string;
  account_name: string;
  amount: string;
  content: string;
  qr_image_url: string;
};
export type PayPalPayment = { kind: "PAYPAL"; approve_url: string };
export type CreatedOrder = Order & { payment: VietQrPayment | PayPalPayment };
export type RefundRequest = {
  id: string;
  order_id: string;
  provider: "SEPAY" | "PAYPAL";
  amount: string;
  currency: "VND" | "USD";
  status: "OPEN" | "DONE";
  provider_refund_id?: string;
  note?: string;
  created_at: string;
  completed_at?: string;
};
export type UnmatchedTransaction = {
  id: number;
  provider: "SEPAY" | "PAYPAL";
  amount: string;
  currency: "VND" | "USD";
  occurred_at: string;
  unmatched_reason: "NO_CODE" | "UNKNOWN_CODE" | "AMBIGUOUS" | "ALREADY_PAID" | "AMOUNT_LOW";
  candidate_codes: string[];
  content?: string | null;
};
export type Resolution = "REFUNDED_MANUALLY" | "IGNORED";
export type Ok = { ok: boolean };
export type WebhookAck = { success: true };

const seg = encodeURIComponent;
const acc = (accountId: string) => `account_id=${encodeURIComponent(accountId)}`;

export function listProducts(): Promise<Product[]> {
  return callJson<Product[]>("payment", "/products?site=tuvi&currency=VND");
}
/** A SePay (VietQR) order; the caller has collected the "Tôi đủ 18 tuổi" confirmation. */
export function createOrder(accountId: string, productCode: ProductCode): Promise<CreatedOrder> {
  return callJson<CreatedOrder>("payment", "/orders", {
    method: "POST",
    body: {
      account_id: accountId,
      site: "tuvi",
      product_code: productCode,
      currency: "VND",
      provider: "SEPAY",
      adult_confirmed: true,
    },
  });
}
export function getOrder(id: string, accountId: string): Promise<Order> {
  return callJson<Order>("payment", `/orders/${seg(id)}?${acc(accountId)}`);
}
export function listOrders(accountId: string): Promise<Order[]> {
  return callJson<Order[]>("payment", `/orders?${acc(accountId)}`);
}
export function requestRefund(orderId: string, accountId: string): Promise<RefundRequest> {
  return callJson<RefundRequest>("payment", `/orders/${seg(orderId)}/refund-requests`, {
    method: "POST",
    body: { account_id: accountId },
  });
}

// Operator API (X-Admin-Token).
export function listUnmatched(): Promise<UnmatchedTransaction[]> {
  return callJson<UnmatchedTransaction[]>("payment", "/admin/unmatched-transactions", { admin: true });
}
export function assignUnmatched(id: number, orderCode: string): Promise<Ok> {
  return callJson<Ok>("payment", `/admin/unmatched-transactions/${seg(String(id))}/assign`, {
    method: "POST",
    admin: true,
    body: { order_code: orderCode },
  });
}
export function resolveUnmatched(id: number, resolution: Resolution, note?: string): Promise<Ok> {
  return callJson<Ok>("payment", `/admin/unmatched-transactions/${seg(String(id))}/resolve`, {
    method: "POST",
    admin: true,
    body: { resolution, note: note ?? null },
  });
}
export function listRefundRequests(): Promise<RefundRequest[]> {
  return callJson<RefundRequest[]>("payment", "/admin/refund-requests", { admin: true });
}
export function completeRefundRequest(id: string): Promise<RefundRequest> {
  return callJson<RefundRequest>("payment", `/admin/refund-requests/${seg(id)}/complete`, {
    method: "POST",
    admin: true,
    body: {},
  });
}

/**
 * Posts a SePay webhook body (dev transfer simulator), authenticated like SePay itself:
 * `Authorization: Apikey <secret>` or `X-SePay-Signature: hex(HMAC-SHA256(raw body))`.
 */
export function postSepayWebhook(body: object): Promise<WebhookAck> {
  const c = cfg();
  const raw = JSON.stringify(body);
  const headers: Record<string, string> =
    c.sepayWebhookAuth === "hmac-sha256"
      ? { "x-sepay-signature": createHmac("sha256", c.sepayWebhookSecret).update(raw).digest("hex") }
      : { authorization: `Apikey ${c.sepayWebhookSecret}` };
  return callJson<WebhookAck>("payment", "/webhooks/sepay", { method: "POST", body, headers });
}
