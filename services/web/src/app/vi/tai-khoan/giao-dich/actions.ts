"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid } from "@/lib/ids";
import { requestRefund } from "@/lib/payment";
import { getViewer } from "@/lib/session";
import { UpstreamError, UpstreamProblem } from "@/lib/upstream";

export type RefundState = { ok: string; error?: never } | { error: string; ok?: never } | null;

const PAGE = "/vi/tai-khoan/giao-dich";
const REFUSED: Record<string, string> = {
  credits_used: "Lượt của gói này đã được dùng, không hoàn tiền được.",
  not_refundable: "Đơn này không hoàn tiền tự động được.",
};

/** "Yêu cầu hoàn tiền" (after the dialog's confirmation) for one of the viewer's orders. */
export async function refundAction(_prev: RefundState, fd: FormData): Promise<RefundState> {
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect(`/vi/dang-nhap?next=${PAGE}`);
  const orderId = fd.get("order_id");
  if (!isUuid(orderId)) return { error: "Không thấy đơn này." };
  try {
    await requestRefund(orderId, viewer.accountId);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 409) {
      return { error: REFUSED[e.reason ?? ""] ?? REFUSED.not_refundable };
    }
    if (e instanceof UpstreamProblem && e.status === 404) return { error: "Không thấy đơn này." };
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) {
      return { error: "Ta chưa ghi nhận được, con thử lại sau." };
    }
    throw e;
  }
  revalidatePath(PAGE);
  return { ok: "Ta đã ghi nhận. Tiền sẽ được chuyển lại trong vài ngày làm việc." };
}
