"use server";

import { revalidatePath } from "next/cache";
import { notFound } from "next/navigation";
import { cfg } from "@/lib/config";
import { assignUnmatched, completeRefundRequest, resolveUnmatched, type Resolution } from "@/lib/payment";
import { unmatchedReasonLabel } from "@/lib/products";
import { getViewer } from "@/lib/session";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

/** Never carries the admin token or upstream detail: labels only. */
export type AdminState = { ok: string; error?: never } | { error: string; ok?: never } | null;

const PAGE = "/vi/quan-tri";
const RESOLUTIONS: readonly Resolution[] = ["REFUNDED_MANUALLY", "IGNORED"];

/** Every admin action re-checks first: an account in ADMIN_ACCOUNT_IDS, with ADMIN_API_TOKEN set. */
async function requireAdmin(): Promise<void> {
  const viewer = await getViewer();
  if (viewer.kind !== "account" || !viewer.isAdmin || !cfg().adminToken) notFound();
}

function txId(fd: FormData): number | null {
  const raw = fd.get("id");
  return typeof raw === "string" && /^[1-9]\d{0,17}$/.test(raw) ? Number(raw) : null;
}

function upstreamMessage(e: unknown): AdminState {
  if (e instanceof UpstreamProblem && e.status === 401) return { error: "payment-svc từ chối admin token." };
  if (e instanceof UpstreamProblem && e.status === 404) return { error: "Không thấy giao dịch" };
  if (e instanceof UpstreamError || e instanceof UpstreamProblem || e instanceof UpstreamValidation) {
    return { error: "payment-svc chưa trả lời, thử lại sau." };
  }
  throw e;
}

/** "Gán đơn": pays a PENDING order with an unmatched transfer. */
export async function assignAction(_prev: AdminState, fd: FormData): Promise<AdminState> {
  await requireAdmin();
  const id = txId(fd);
  const raw = fd.get("order_code");
  const code = typeof raw === "string" ? raw.trim().toUpperCase() : "";
  if (id === null) return { error: "Giao dịch không hợp lệ." };
  if (!/^[A-Z0-9]{4,32}$/.test(code)) return { error: "Mã đơn không hợp lệ." };
  try {
    await assignUnmatched(id, code);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) return { error: "Không thấy đơn" };
    if (e instanceof UpstreamProblem && e.status === 409) return { error: unmatchedReasonLabel(e.reason) };
    return upstreamMessage(e);
  }
  revalidatePath(PAGE);
  return { ok: `Đã gán giao dịch #${id} cho đơn ${code}.` };
}

/** "Đóng": the transfer was dealt with outside the system. */
export async function resolveAction(_prev: AdminState, fd: FormData): Promise<AdminState> {
  await requireAdmin();
  const id = txId(fd);
  const resolution = fd.get("resolution");
  const rawNote = fd.get("note");
  const note = typeof rawNote === "string" && rawNote.trim() ? rawNote.trim().slice(0, 500) : undefined;
  if (id === null) return { error: "Giao dịch không hợp lệ." };
  if (!RESOLUTIONS.includes(resolution as Resolution)) return { error: "Chọn cách đóng." };
  try {
    await resolveUnmatched(id, resolution as Resolution, note);
  } catch (e) {
    return upstreamMessage(e);
  }
  revalidatePath(PAGE);
  return { ok: `Đã đóng giao dịch #${id}.` };
}

/** "Đã chuyển trả": the operator sent the money back; the order becomes REFUNDED. */
export async function completeRefundAction(_prev: AdminState, fd: FormData): Promise<AdminState> {
  await requireAdmin();
  const id = fd.get("id");
  if (typeof id !== "string" || !/^[0-9a-zA-Z-]{1,64}$/.test(id)) return { error: "Yêu cầu không hợp lệ." };
  try {
    await completeRefundRequest(id);
  } catch (e) {
    return upstreamMessage(e);
  }
  revalidatePath(PAGE);
  return { ok: "Đã ghi nhận chuyển trả." };
}
