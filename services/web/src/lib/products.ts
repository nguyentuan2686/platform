// Labels for products, orders and the credit ledger. Client-safe (no server imports).
import type { LedgerEntry } from "@/lib/identity";
import type { Order, OrderStatus, ProductCode, UnmatchedTransaction } from "@/lib/payment";

/** Display order of the packs on /vi/mua. */
export const PRODUCT_ORDER: readonly ProductCode[] = ["CREDIT_1", "CREDIT_5", "PASS_MONTH", "PASS_YEAR"];

const COPY: Record<ProductCode, { title: string; hint: string }> = {
  CREDIT_1: { title: "1 lượt", hint: "Thử một câu hỏi" },
  CREDIT_5: { title: "5 lượt", hint: "23.800đ mỗi lượt" },
  PASS_MONTH: { title: "Thẻ tháng", hint: "30 ngày · 2 lượt hỏi · xem ngày chi tiết (sắp có)" },
  PASS_YEAR: { title: "Thẻ năm", hint: "365 ngày · hỏi không giới hạn · bài vận năm (sắp có)" },
};

export function isProductCode(v: unknown): v is ProductCode {
  return typeof v === "string" && Object.hasOwn(COPY, v);
}

export function productCopy(code: ProductCode): { title: string; hint: string } {
  return COPY[code];
}

const STATUS: Record<OrderStatus, string> = {
  PENDING: "Chờ thanh toán",
  EXPIRED: "Quá hạn",
  PAID: "Đã thanh toán",
  REFUND_REQUESTED: "Đang hoàn tiền",
  REFUNDED: "Đã hoàn tiền",
};
export function statusLabel(s: OrderStatus): string {
  return STATUS[s];
}

const REASON: Record<LedgerEntry["reason"], string> = {
  signup_bonus: "Tặng khi tạo tài khoản",
  purchase: "Nạp lượt",
  consume: "Dùng lượt",
  refund: "Hoàn lượt",
  reversal: "Thu hồi khi hoàn tiền",
};
export function ledgerReasonLabel(r: LedgerEntry["reason"]): string {
  return REASON[r];
}

const UNMATCHED: Record<UnmatchedTransaction["unmatched_reason"] | "ORDER_NOT_PENDING", string> = {
  NO_CODE: "Thiếu mã",
  UNKNOWN_CODE: "Mã lạ",
  AMBIGUOUS: "Nhiều mã",
  ALREADY_PAID: "Đơn đã trả",
  AMOUNT_LOW: "Thiếu tiền",
  ORDER_NOT_PENDING: "Đơn không còn chờ thanh toán",
};
/** An unmatched reason (`AMOUNT_LOW`) or a 409 assign refusal (`amount_low`) as a label. */
export function unmatchedReasonLabel(reason: string | undefined): string {
  const key = (reason ?? "").toUpperCase();
  return Object.hasOwn(UNMATCHED, key) ? UNMATCHED[key as keyof typeof UNMATCHED] : "Bị từ chối";
}

/** Only a PAID credit pack is refunded automatically (passes are refunded by hand). */
export function isRefundable(order: Pick<Order, "status" | "product_code">): boolean {
  return order.status === "PAID" && order.product_code.startsWith("CREDIT_");
}
