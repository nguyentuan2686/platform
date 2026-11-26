import { describe, expect, it } from "vitest";
import {
  isProductCode,
  isRefundable,
  ledgerReasonLabel,
  PRODUCT_ORDER,
  productCopy,
  statusLabel,
  unmatchedReasonLabel,
} from "@/lib/products";

describe("products", () => {
  it("has the copy of every product", () => {
    expect(productCopy("CREDIT_1")).toEqual({ title: "1 lượt", hint: "Thử một câu hỏi" });
    expect(productCopy("CREDIT_5")).toEqual({ title: "5 lượt", hint: "23.800đ mỗi lượt" });
    expect(productCopy("PASS_MONTH")).toEqual({
      title: "Thẻ tháng",
      hint: "30 ngày · 2 lượt hỏi · xem ngày chi tiết (sắp có)",
    });
    expect(productCopy("PASS_YEAR")).toEqual({
      title: "Thẻ năm",
      hint: "365 ngày · hỏi không giới hạn · bài vận năm (sắp có)",
    });
    expect(PRODUCT_ORDER).toEqual(["CREDIT_1", "CREDIT_5", "PASS_MONTH", "PASS_YEAR"]);
  });

  it("recognises product codes only", () => {
    expect(isProductCode("CREDIT_5")).toBe(true);
    expect(isProductCode("CREDIT_12")).toBe(false);
    expect(isProductCode(null)).toBe(false);
  });

  it("labels order statuses", () => {
    expect(statusLabel("PENDING")).toBe("Chờ thanh toán");
    expect(statusLabel("EXPIRED")).toBe("Quá hạn");
    expect(statusLabel("PAID")).toBe("Đã thanh toán");
    expect(statusLabel("REFUND_REQUESTED")).toBe("Đang hoàn tiền");
    expect(statusLabel("REFUNDED")).toBe("Đã hoàn tiền");
  });

  it("labels ledger reasons", () => {
    expect(ledgerReasonLabel("signup_bonus")).toBe("Tặng khi tạo tài khoản");
    expect(ledgerReasonLabel("purchase")).toBe("Nạp lượt");
    expect(ledgerReasonLabel("consume")).toBe("Dùng lượt");
    expect(ledgerReasonLabel("refund")).toBe("Hoàn lượt");
    expect(ledgerReasonLabel("reversal")).toBe("Thu hồi khi hoàn tiền");
  });

  it("labels unmatched reasons, also from a 409 snake_case reason", () => {
    expect(unmatchedReasonLabel("NO_CODE")).toBe("Thiếu mã");
    expect(unmatchedReasonLabel("UNKNOWN_CODE")).toBe("Mã lạ");
    expect(unmatchedReasonLabel("AMBIGUOUS")).toBe("Nhiều mã");
    expect(unmatchedReasonLabel("ALREADY_PAID")).toBe("Đơn đã trả");
    expect(unmatchedReasonLabel("amount_low")).toBe("Thiếu tiền");
    expect(unmatchedReasonLabel("order_not_pending")).toBe("Đơn không còn chờ thanh toán");
    expect(unmatchedReasonLabel(undefined)).toBe("Bị từ chối");
  });

  it("refunds only PAID credit packs", () => {
    expect(isRefundable({ status: "PAID", product_code: "CREDIT_1" })).toBe(true);
    expect(isRefundable({ status: "PAID", product_code: "CREDIT_5" })).toBe(true);
    expect(isRefundable({ status: "PAID", product_code: "PASS_MONTH" })).toBe(false);
    expect(isRefundable({ status: "PAID", product_code: "PASS_YEAR" })).toBe(false);
    for (const s of ["PENDING", "EXPIRED", "REFUND_REQUESTED", "REFUNDED"] as const) {
      expect(isRefundable({ status: s, product_code: "CREDIT_5" })).toBe(false);
    }
  });
});
