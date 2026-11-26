"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { isUuid } from "@/lib/ids";
import { requestRefund } from "@/lib/payment";
import { getViewer } from "@/lib/session";
import { logRefusalOrThrow } from "@/lib/degrade";
import { UpstreamProblem } from "@/lib/upstream";

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
    // Expected: the two 409s the page explains, and 404 (not this account's order).
    if (e instanceof UpstreamProblem && e.status === 409 && e.reason && Object.hasOwn(REFUSED, e.reason)) {
      return { error: REFUSED[e.reason] };
    }
    if (e instanceof UpstreamProblem && e.status === 404) return { error: "Không thấy đơn này." };
    logRefusalOrThrow(e, "refund"); // an unknown 409, 401/403, a 400: logged; down: logged by http.ts
    return { error: "Ta chưa ghi nhận được, con thử lại sau." };
  }
  revalidatePath(PAGE);
  return { ok: "Ta đã ghi nhận. Tiền sẽ được chuyển lại trong vài ngày làm việc." };
}
