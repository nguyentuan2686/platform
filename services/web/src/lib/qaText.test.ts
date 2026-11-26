import { describe, expect, it } from "vitest";
import { AI_LABEL, qaStatusLabel, reasonMessage } from "@/lib/qaText";

describe("reasonMessage", () => {
  it.each([
    ["no_credits", "Con đã hết lượt.", "/vi/mua", "Nạp lượt"],
    ["free_quota", "Hôm nay con đã hỏi đủ câu miễn phí.", "/vi/mua", "Nạp lượt"],
    ["daily_cap", "Hôm nay con hỏi nhiều rồi, mai ta luận tiếp.", undefined, undefined],
    ["account_required", "Con cần đăng nhập để dùng lượt.", "/vi/dang-nhap?next=/vi/hoi", "Đăng nhập"],
    ["no_profile", "Con lập lá số trước đã.", "/vi", "Lập lá số"],
    ["reading_in_progress", "Câu này ta đang luận, con chờ chút.", undefined, undefined],
    ["reading_conflict", "Con gửi lại câu hỏi nhé.", undefined, undefined],
    ["reading_refunded", "Con gửi lại câu hỏi nhé.", undefined, undefined],
    ["unauthenticated", "Phiên đã hết, con đăng nhập lại nhé.", "/vi/dang-nhap?next=/vi/hoi", "Đăng nhập"],
    ["unavailable", "Lão Thiên Cơ cần một lát.", undefined, undefined],
    ["whatever", "Lão Thiên Cơ cần một lát.", undefined, undefined],
  ])("%s", (reason, text, href, cta) => {
    expect(reasonMessage(reason)).toEqual({ text, ...(href ? { href, cta } : {}) });
  });
});

describe("qaStatusLabel", () => {
  it.each([
    ["ANSWERED", "Đã luận"],
    ["PENDING", "Đang soạn"],
    ["RETRYING", "Đang soạn"],
    ["REFUND_PENDING", "Đang hoàn lượt"],
    ["REFUNDED", "Đã hoàn lượt"],
    ["REFUSED", "Ta không luận câu này"],
    ["FAILED", "Chưa luận được"],
  ] as const)("%s", (status, label) => {
    expect(qaStatusLabel(status)).toBe(label);
  });
});

it("labels AI content", () => {
  expect(AI_LABEL).toBe("Nội dung do AI biên soạn từ kho tư liệu.");
});
