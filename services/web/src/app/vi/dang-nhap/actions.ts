"use server";

import { redirect } from "next/navigation";
import { requestMagicLink, verifyMagicLink } from "@/lib/identity";
import { completeLogin } from "@/lib/login";
import { rememberNext, takeNext } from "@/lib/loginCookies";
import { loginGuest } from "@/lib/session";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

export type LoginState = { sent: true } | { errors: { email: string } } | null;
export type ConfirmState = { error: string } | null;

const BAD_EMAIL = "Email chưa đúng.";
const NOT_SENT = "Chưa gửi được thư, con thử lại sau ít phút.";
const EXPIRED = "Link đã hết hạn hoặc đã dùng. Con xin link mới nhé.";
const UNAVAILABLE = "Lão Thiên Cơ cần một lát. Con thử lại sau ít phút nhé.";
const MAX_EMAIL = 254;
const MAX_TOKEN = 512;

const str = (fd: FormData, k: string): string | null => {
  const v = fd.get(k);
  return typeof v === "string" ? v : null;
};

/** Emails a login link. identity answers 202 whatever the address, so the page never tells them apart. */
export async function requestLogin(_prev: LoginState, fd: FormData): Promise<LoginState> {
  const email = (str(fd, "email") ?? "").trim();
  if (!email || email.length > MAX_EMAIL || !email.includes("@")) return { errors: { email: BAD_EMAIL } };
  try {
    const guest = await loginGuest({ create: true });
    await requestMagicLink(email, guest?.token);
  } catch (e) {
    if (e instanceof UpstreamValidation) return { errors: { email: BAD_EMAIL } };
    // 503 from the mail server, a timeout, or a refusal (logged by http.ts / no PII): retry later.
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) return { errors: { email: NOT_SENT } };
    throw e;
  }
  await rememberNext(str(fd, "next"));
  return { sent: true };
}

/** Redeems the emailed link (POST only: a GET of the page does nothing). */
export async function confirmLogin(_prev: ConfirmState, fd: FormData): Promise<ConfirmState> {
  const token = str(fd, "token") ?? "";
  if (!token || token.length > MAX_TOKEN) return { error: EXPIRED };
  try {
    const guest = await loginGuest({ create: false });
    const login = await verifyMagicLink(token, guest?.token);
    await completeLogin(login, guest);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 401) return { error: EXPIRED };
    if (e instanceof UpstreamValidation) return { error: EXPIRED };
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) return { error: UNAVAILABLE };
    throw e;
  }
  redirect(await takeNext());
}
