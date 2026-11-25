import "server-only";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { cfg } from "@/lib/config";
import { safeNext } from "@/lib/safeNext";
import { OIDC_CALLBACK_PATH } from "@/lib/googleLogin";

const NEXT_COOKIE = "tv_next";
const NEXT_MAX_AGE_S = 15 * 60;
const OIDC_COOKIE = "tv_oidc";
const OIDC_MAX_AGE_S = 10 * 60;

const base = (path: string) => ({ httpOnly: true, sameSite: "lax" as const, path, secure: cfg().cookieSecure });

/** Remembers where to land after login (validated now and again when taken), for 15 minutes. */
export async function rememberNext(raw: string | null | undefined): Promise<void> {
  (await cookies()).set(NEXT_COOKIE, safeNext(raw), { ...base("/"), maxAge: NEXT_MAX_AGE_S });
}

/** The post-login path (default /vi/la-so); the cookie is cleared. */
export async function takeNext(): Promise<string> {
  const jar = await cookies();
  const v = jar.get(NEXT_COOKIE)?.value;
  if (v !== undefined) jar.delete({ name: NEXT_COOKIE, ...base("/") });
  return safeNext(v);
}

export async function setOidcState(state: string): Promise<void> {
  (await cookies()).set(OIDC_COOKIE, state, { ...base(OIDC_CALLBACK_PATH), maxAge: OIDC_MAX_AGE_S });
}

/** The OIDC state stored at start, if any; single use (the cookie is cleared). */
export async function takeOidcState(): Promise<string | undefined> {
  const jar = await cookies();
  const v = jar.get(OIDC_COOKIE)?.value;
  if (v !== undefined) jar.delete({ name: OIDC_COOKIE, ...base(OIDC_CALLBACK_PATH) });
  return v || undefined;
}

// ---- Login binding: merge guest data only into a login started in this browser ----

const LOGIN_MAX_AGE_S = 15 * 60; // the magic link's lifetime
const PENDING_MAX_AGE_S = 10 * 60;
const B64URL_43 = /^[A-Za-z0-9_-]{43}$/;

const prefixed = (name: string) => (cfg().cookieSecure ? `__Host-${name}` : name);
const loginCookie = () => prefixed("tv_login");
const pendingCookie = () => prefixed("tv_pending");

/** sha256(nonce:session) - ties the nonce to the session token without storing the token. */
const tag = (nonce: string, session: string) =>
  createHash("sha256").update(`tv_login:${nonce}:${session}`).digest("base64url");

/**
 * When a login starts (magic-link request, Google start): a random nonce bound to this browser's
 * session token, so the confirmation can tell a login this browser asked for from a forwarded link.
 */
export async function bindLoginToSession(session: string): Promise<void> {
  const nonce = randomBytes(32).toString("base64url");
  (await cookies()).set(loginCookie(), `${nonce}.${tag(nonce, session)}`, { ...base("/"), maxAge: LOGIN_MAX_AGE_S });
}

/** True only when this browser started the login with this very session (constant time); single use. */
export async function takeLoginBinding(session: string | undefined): Promise<boolean> {
  const jar = await cookies();
  const name = loginCookie();
  const v = jar.get(name)?.value;
  if (v === undefined) return false;
  jar.delete({ name, ...base("/") });
  if (!session) return false;
  const [nonce, got, ...rest] = v.split(".");
  if (rest.length > 0 || !B64URL_43.test(nonce ?? "") || !B64URL_43.test(got ?? "")) return false;
  return timingSafeEqual(Buffer.from(got), Buffer.from(tag(nonce, session)));
}

export type PendingLogin = { token: string; expiresAt: string };

/** A verified login waiting for the user's yes on the interstitial (a link this browser did not ask for). */
export async function setPendingLogin(token: string, expiresAt: string): Promise<void> {
  const ms = Date.parse(expiresAt);
  const value = `${token}.${Number.isFinite(ms) ? ms : Date.now()}`;
  (await cookies()).set(pendingCookie(), value, { ...base("/"), maxAge: PENDING_MAX_AGE_S });
}

/** The pending login, if any; single use. */
export async function takePendingLogin(): Promise<PendingLogin | null> {
  const jar = await cookies();
  const name = pendingCookie();
  const v = jar.get(name)?.value;
  if (v === undefined) return null;
  jar.delete({ name, ...base("/") });
  const m = /^([A-Za-z0-9_-]{1,128})\.(\d{1,15})$/.exec(v);
  if (!m) return null;
  return { token: m[1], expiresAt: new Date(Number(m[2])).toISOString() };
}
