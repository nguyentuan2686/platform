import "server-only";
import { cache } from "react";
import { cookies } from "next/headers";
import { cfg } from "@/lib/config";
import { UpstreamProblem } from "@/lib/http";
import { createGuest, entitlements, me, type Entitlements } from "@/lib/identity";

export type Viewer =
  | { kind: "anon" }
  | { kind: "guest"; guestId: string; token: string }
  | {
      kind: "account";
      accountId: string;
      emailMasked: string;
      token: string;
      entitlements: Entitlements;
      isAdmin: boolean;
    };

const ONE_DAY_S = 86400;

/** `__Host-` (Secure, Path=/, no Domain) in prod; a plain name for local http. */
export function sessionCookieName(): string {
  return cfg().cookieSecure ? "__Host-tv_sid" : "tv_sid";
}

export async function readSessionToken(): Promise<string | undefined> {
  const v = (await cookies()).get(sessionCookieName())?.value;
  return v ? v : undefined;
}

/** HttpOnly; SameSite=Lax; Path=/ (+ Secure), living until the session's expires_at. */
export async function setSessionCookie(token: string, expiresAt: string): Promise<void> {
  const ms = Date.parse(expiresAt) - Date.now();
  // Unparsable expires_at (never expected): one day rather than a cookie deleted at once.
  const maxAge = Number.isFinite(ms) ? Math.max(0, Math.floor(ms / 1000)) : ONE_DAY_S;
  (await cookies()).set(sessionCookieName(), token, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: cfg().cookieSecure,
    maxAge,
  });
}

export async function clearSessionCookie(): Promise<void> {
  (await cookies()).delete({ name: sessionCookieName(), path: "/", secure: cfg().cookieSecure, httpOnly: true, sameSite: "lax" });
}

/**
 * The session token, creating a guest (and its cookie) when there is none. `renew` replaces a
 * cookie identity no longer accepts. Call only from server actions or route handlers (sets a cookie).
 */
export async function ensureSession(opts: { renew?: boolean } = {}): Promise<string> {
  if (!opts.renew) {
    const existing = await readSessionToken();
    if (existing) return existing;
  }
  const g = await createGuest();
  await setSessionCookie(g.session_token, g.expires_at);
  return g.session_token;
}

/** Who is browsing, once per request. A cookie identity rejects (401) reads as anonymous. */
export const getViewer = cache(async (): Promise<Viewer> => {
  const token = await readSessionToken();
  if (!token) return { kind: "anon" };
  try {
    const who = await me(token);
    if (who.kind === "guest") return { kind: "guest", guestId: who.guest_id, token };
    return {
      kind: "account",
      accountId: who.account_id,
      emailMasked: who.email_masked ?? "",
      token,
      entitlements: await entitlements(token),
      isAdmin: cfg().adminIds.has(who.account_id),
    };
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 401) return { kind: "anon" };
    throw e;
  }
});
