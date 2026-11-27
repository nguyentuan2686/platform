"use server";

import { redirect } from "next/navigation";
import { logout, me, requestMagicLink, verifyMagicLink } from "@/lib/identity";
import { clientIp } from "@/lib/clientIp";
import { logRefusalOrThrow } from "@/lib/degrade";
import { completeLogin } from "@/lib/login";
import {
  bindLoginToSession,
  clearLoginBinding,
  peekLoginBinding,
  rememberNext,
  setPendingLogin,
  takeNext,
  takePendingLogin,
} from "@/lib/loginCookies";
import { loginGuest, readSessionToken, setSessionCookie } from "@/lib/session";
import { UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

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
    logRefusalOrThrow(e, "login-discard"); // best effort: the session also expires by itself
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
    await requestMagicLink(email, guest?.token, await clientIp());
    session = guest?.token ?? (await readSessionToken());
  } catch (e) {
    if (e instanceof UpstreamValidation) return { errors: { email: BAD_EMAIL } };
    // 503 from the mail server or a timeout (logged by http.ts), or an unexpected refusal (logged
    // here, no email): retry later. A bug rethrows.
    logRefusalOrThrow(e, "login-request");
    return { errors: { email: NOT_SENT } };
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
    // Peek, not take: a transient failure below leaves the binding, so the retry still merges.
    const bound = await peekLoginBinding(guest?.token ?? (await readSessionToken()));
    if (bound) {
      const login = await verifyMagicLink(token, guest?.token);
      await clearLoginBinding();
      await completeLogin(login, guest);
    } else {
      const login = await verifyMagicLink(token, undefined);
      await clearLoginBinding();
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
    // A dead link (401/400) ends this login: consume the binding. Identity down, a timeout or an
    // unexpected refusal keeps it, so "thử lại" from this browser still carries the guest over.
    if ((e instanceof UpstreamProblem && e.status === 401) || e instanceof UpstreamValidation) {
      await clearLoginBinding();
      return { error: EXPIRED };
    }
    logRefusalOrThrow(e, "login-confirm");
    return { error: UNAVAILABLE };
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
  await clearLoginBinding();
  if (pending) await discard(pending.token);
  redirect("/vi");
}
