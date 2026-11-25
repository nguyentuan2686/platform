"use server";

import { redirect } from "next/navigation";
import { logout, me, requestMagicLink, verifyMagicLink } from "@/lib/identity";
import { completeLogin } from "@/lib/login";
import {
  bindLoginToSession,
  rememberNext,
  setPendingLogin,
  takeLoginBinding,
  takeNext,
  takePendingLogin,
} from "@/lib/loginCookies";
import { loginGuest, readSessionToken, setSessionCookie } from "@/lib/session";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

export type LoginState = { sent: true } | { errors: { email: string } } | null;
/** `pending`: a link this browser did not ask for, verified but waiting for the user's yes. */
export type ConfirmState = { error: string } | { pending: { emailMasked: string } } | null;

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

/** Revokes a session we will not use; identity trouble only means it expires on its own. */
async function discard(token: string): Promise<void> {
  try {
    await logout(token);
  } catch (e) {
    if (!(e instanceof UpstreamError || e instanceof UpstreamProblem)) throw e;
  }
}

/**
 * Emails a login link. identity answers 202 whatever the address, so the page never tells them
 * apart. The request is bound to this browser's session (tv_login), so only a confirmation from
 * here carries the guest's data over.
 */
export async function requestLogin(_prev: LoginState, fd: FormData): Promise<LoginState> {
  const email = (str(fd, "email") ?? "").trim();
  if (!email || email.length > MAX_EMAIL || !email.includes("@")) return { errors: { email: BAD_EMAIL } };
  let session: string | undefined;
  try {
    const guest = await loginGuest({ create: true });
    await requestMagicLink(email, guest?.token);
    session = guest?.token ?? (await readSessionToken());
  } catch (e) {
    if (e instanceof UpstreamValidation) return { errors: { email: BAD_EMAIL } };
    // 503 from the mail server, a timeout, or a refusal (logged by http.ts / no PII): retry later.
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) return { errors: { email: NOT_SENT } };
    throw e;
  }
  if (session) await bindLoginToSession(session);
  await rememberNext(str(fd, "next"));
  return { sent: true };
}

/**
 * Redeems the emailed link (POST only: a GET of the page does nothing).
 * - Requested in this browser (tv_login matches the current session): log in and merge the guest.
 * - Otherwise (another browser, or a link someone else sent): verify WITHOUT the guest bearer, merge
 *   nothing, and ask first, naming the account (masked) the link opens.
 */
export async function confirmLogin(_prev: ConfirmState, fd: FormData): Promise<ConfirmState> {
  const token = str(fd, "token") ?? "";
  if (!token || token.length > MAX_TOKEN) return { error: EXPIRED };
  try {
    const guest = await loginGuest({ create: false });
    const bound = await takeLoginBinding(guest?.token ?? (await readSessionToken()));
    if (bound) {
      await completeLogin(await verifyMagicLink(token, guest?.token), guest);
    } else {
      const login = await verifyMagicLink(token, undefined);
      let emailMasked: string;
      try {
        const who = await me(login.session_token);
        emailMasked = who.kind === "account" ? (who.email_masked ?? "") : "";
      } catch (e) {
        await discard(login.session_token);
        throw e;
      }
      await setPendingLogin(login.session_token, login.expires_at);
      return { pending: { emailMasked } };
    }
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 401) return { error: EXPIRED };
    if (e instanceof UpstreamValidation) return { error: EXPIRED };
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) return { error: UNAVAILABLE };
    throw e;
  }
  redirect(await takeNext());
}

/** Interstitial "yes": switch this browser to that account. Nothing on this device is carried over. */
export async function acceptPendingLogin(): Promise<void> {
  const pending = await takePendingLogin();
  if (!pending) redirect("/vi/dang-nhap");
  await setSessionCookie(pending.token, pending.expiresAt);
  redirect(await takeNext());
}

/** Interstitial "no": revoke the session the link opened and keep browsing as before. */
export async function cancelPendingLogin(): Promise<void> {
  const pending = await takePendingLogin();
  if (pending) await discard(pending.token);
  redirect("/vi");
}
