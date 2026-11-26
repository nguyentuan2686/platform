"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { logRefusalOrThrow } from "@/lib/degrade";
import { deleteReadings } from "@/lib/qa";
import { getViewer } from "@/lib/session";
import { UpstreamProblem } from "@/lib/upstream";

export type DeleteHistoryState = { ok: true; error?: never } | { error: string; ok?: never } | null;

const PAGE = "/vi/lich-su";

/** "Xóa toàn bộ lịch sử hỏi", after the dialog's confirmation (`confirm=1`). */
export async function deleteHistoryAction(_prev: DeleteHistoryState, fd: FormData): Promise<DeleteHistoryState> {
  const viewer = await getViewer();
  if (viewer.kind === "anon") redirect("/vi/hoi");
  if (fd.get("confirm") !== "1") return null;
  try {
    await deleteReadings(viewer.token);
  } catch (e) {
    // Expected: an answer or a refund still on its way blocks the deletion.
    if (e instanceof UpstreamProblem && e.status === 409) {
      return { error: "Còn câu đang soạn, con chờ xong rồi xóa nhé." };
    }
    logRefusalOrThrow(e, "qa-delete"); // 401/403, an unknown 4xx: logged; down: logged by http.ts; a bug rethrows
    return { error: "Lão Thiên Cơ cần một lát. Con thử lại sau nhé." };
  }
  revalidatePath(PAGE);
  return { ok: true };
}
