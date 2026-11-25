import "server-only";
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
