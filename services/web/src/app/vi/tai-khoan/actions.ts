"use server";

import { redirect } from "next/navigation";
import * as identity from "@/lib/identity";
import { deleteReadings } from "@/lib/qa";
import { clearSessionCookie, getViewer } from "@/lib/session";
import { UpstreamError, UpstreamProblem } from "@/lib/upstream";

const ACCOUNT_PAGE = "/vi/tai-khoan";
const LOGIN_FOR_ACCOUNT = "/vi/dang-nhap?next=/vi/tai-khoan";

/** The account bearer, or a redirect to login. */
async function accountToken(): Promise<string> {
  const viewer = await getViewer();
  if (viewer.kind !== "account") redirect(LOGIN_FOR_ACCOUNT);
  return viewer.token;
}

/** "Đổi lịch": the same label and birth, the other calendar (allowed even when the birth is locked). */
export async function changeCalendar(): Promise<void> {
  const token = await accountToken();
  const profile = await identity.getProfile(token);
  if (!profile) redirect("/vi");
  await identity.putProfile(token, {
    label: profile.label,
    birth: profile.birth,
    calendar: profile.calendar === "VN" ? "CN" : "VN",
  });
  redirect(ACCOUNT_PAGE);
}

/**
 * "Xóa lá số", from the confirmation dialog only (`confirm=1`): the Q&A history first, then the
 * chart, so a failure never leaves history without its chart.
 */
export async function deleteProfile(fd: FormData): Promise<void> {
  const token = await accountToken();
  if (fd.get("confirm") !== "1") redirect(ACCOUNT_PAGE);
  await deleteReadings(token);
  await identity.deleteProfile(token);
  redirect("/vi");
}

/** Revokes the session at identity (already revoked or identity down: still logged out here). */
export async function logoutAction(): Promise<void> {
  try {
    const viewer = await getViewer();
    if (viewer.kind !== "anon") await identity.logout(viewer.token);
  } catch (e) {
    const ignorable = (e instanceof UpstreamProblem && e.status === 401) || e instanceof UpstreamError;
    if (!ignorable) {
      await clearSessionCookie();
      throw e;
    }
  }
  await clearSessionCookie();
  redirect("/vi");
}
