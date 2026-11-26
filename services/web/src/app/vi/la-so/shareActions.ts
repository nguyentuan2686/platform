"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { logRefusalOrThrow } from "@/lib/degrade";
import { createShare, deleteShare } from "@/lib/identity";
import { getViewer } from "@/lib/session";
import { UpstreamProblem } from "@/lib/upstream";

export type ShareState = { error: string } | null;

const PAGE = "/vi/la-so";
const BUSY: ShareState = { error: "Lão Thiên Cơ cần một lát. Con thử lại sau nhé." };

/** The owner's own session token (guest or account); anyone else goes home. */
async function ownerToken(): Promise<string> {
  const viewer = await getViewer();
  if (viewer.kind === "anon") redirect("/vi");
  return viewer.token;
}

/** "Tạo link công khai": idempotent at identity-svc. */
export async function createShareAction(): Promise<ShareState> {
  const token = await ownerToken();
  try {
    await createShare(token);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 409) return { error: "Con cần lập lá số trước khi chia sẻ." };
    logRefusalOrThrow(e, "share-create");
    return BUSY;
  }
  revalidatePath(PAGE);
  return null;
}

/** "Tắt link": the old id never resolves again. */
export async function deleteShareAction(): Promise<ShareState> {
  const token = await ownerToken();
  try {
    await deleteShare(token);
  } catch (e) {
    logRefusalOrThrow(e, "share-delete");
    return BUSY;
  }
  revalidatePath(PAGE);
  return null;
}
