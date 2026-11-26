import type { QaStatus } from "@/lib/qa";

/** Printed under every Q&A answer, with the server's own disclaimer. */
export const AI_LABEL = "Nội dung do AI biên soạn từ kho tư liệu.";
/** Shown when the stream breaks after the question was sent: the reading is kept server-side. */
export const DROP_TEXT = "Mất kết nối. Bài luận vẫn được lưu, con xem lại trong Lịch sử nhé.";
export const QUEUED_NOTE = "Ta sẽ báo trong Lịch sử khi bài luận xong.";

export type ReasonMessage = { text: string; href?: string; cta?: string };

const BUY = { href: "/vi/mua", cta: "Nạp lượt" };
const LOGIN = { href: "/vi/dang-nhap?next=/vi/hoi", cta: "Đăng nhập" };
const GENERIC = "Lão Thiên Cơ cần một lát.";

/** Lão Thiên Cơ's words for a refused ask (reason codes of the BFF routes and of reading-tuvi). */
export function reasonMessage(reason: string): ReasonMessage {
  switch (reason) {
    case "no_credits":
      return { text: "Con đã hết lượt.", ...BUY };
    case "free_quota":
      return { text: "Hôm nay con đã hỏi đủ câu miễn phí.", ...BUY };
    case "daily_cap":
      return { text: "Hôm nay con hỏi nhiều rồi, mai ta luận tiếp." };
    case "account_required":
      return { text: "Con cần đăng nhập để dùng lượt.", ...LOGIN };
    case "no_profile":
      return { text: "Con lập lá số trước đã.", href: "/vi", cta: "Lập lá số" };
    case "reading_in_progress":
      return { text: "Câu này ta đang luận, con chờ chút." };
    case "reading_conflict":
    case "reading_refunded":
      return { text: "Con gửi lại câu hỏi nhé." };
    case "unauthenticated":
      return { text: "Phiên đã hết, con đăng nhập lại nhé.", ...LOGIN };
    default:
      return { text: GENERIC };
  }
}

const STATUS_LABELS: Record<QaStatus, string> = {
  ANSWERED: "Đã luận",
  PENDING: "Đang soạn",
  RETRYING: "Đang soạn",
  REFUND_PENDING: "Đang hoàn lượt",
  REFUNDED: "Đã hoàn lượt",
  REFUSED: "Ta không luận câu này",
  FAILED: "Chưa luận được",
};

export function qaStatusLabel(status: QaStatus): string {
  return STATUS_LABELS[status] ?? "";
}

/** Statuses in which the answer or the refund is still on its way. */
export function isWaiting(status: string): boolean {
  return status === "PENDING" || status === "RETRYING" || status === "REFUND_PENDING";
}
