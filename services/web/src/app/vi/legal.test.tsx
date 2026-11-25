import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import Privacy, { metadata as privacyMeta } from "@/app/vi/chinh-sach-rieng-tu/page";
import Terms, { metadata as termsMeta } from "@/app/vi/dieu-khoan/page";
import Refund, { metadata as refundMeta } from "@/app/vi/hoan-tien/page";

afterEach(cleanup);
const textOf = (el: React.ReactElement) => render(el).container.textContent ?? "";

describe("legal pages", () => {
  it("are indexable", () => {
    for (const m of [privacyMeta, termsMeta, refundMeta]) {
      expect(m.robots).toMatchObject({ index: true });
      expect(m.title).toBeTruthy();
    }
  });

  it("privacy covers the data, AES, 90-day retention, anonymised cross-border AI transfer and deletion", () => {
    const t = textOf(<Privacy />);
    for (const s of ["ngày giờ sinh", "AES", "90 ngày", "ẩn danh", "nước ngoài", "xóa"]) expect(t).toContain(s);
  });

  it("terms cover the cultural positioning, no giải hạn/cúng/bùa and 18+ to buy", () => {
    const t = textOf(<Terms />);
    for (const s of ["văn hóa", "giải trí", "giải hạn", "cúng", "bùa", "18 tuổi"]) expect(t).toContain(s);
  });

  it("refund covers failed readings, unused packs, passes and how to ask", () => {
    const t = textOf(<Refund />);
    for (const s of ["24 giờ", "chưa dùng", "Thẻ tháng", "thủ công", "Giao dịch"]) expect(t).toContain(s);
  });
});
