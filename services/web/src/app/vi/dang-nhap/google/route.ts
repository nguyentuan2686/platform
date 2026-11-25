import { timingSafeEqual } from "node:crypto";
import { cfg } from "@/lib/config";
import { googleCallback } from "@/lib/identity";
import { GOOGLE_ERROR_PATH, notFound, seeOther } from "@/lib/googleLogin";
import { completeLogin } from "@/lib/login";
import { takeLoginBinding, takeNext, takeOidcState } from "@/lib/loginCookies";
import { loginGuest, readSessionToken } from "@/lib/session";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

export const dynamic = "force-dynamic";

const MAX_PARAM = 2048;

function sameState(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

/**
 * Google's redirect back (the only GET that changes state). It is bound to this browser by the
 * tv_oidc cookie: a missing or different state never reaches identity-svc. The code and state are
 * never logged.
 */
export async function GET(req: Request): Promise<Response> {
  if (!cfg().googleLogin) return notFound();
  const q = new URL(req.url).searchParams;
  const code = q.get("code");
  const state = q.get("state");
  const stored = await takeOidcState();
  if (!code || !state || !stored || code.length > MAX_PARAM || !sameState(state, stored)) {
    return seeOther(GOOGLE_ERROR_PATH);
  }
  try {
    const guest = await loginGuest({ create: false });
    // Merge only into a login this browser started with this same session (tv_login, set at start).
    const bound = await takeLoginBinding(guest?.token ?? (await readSessionToken()));
    const login = await googleCallback(code, state, bound ? guest?.token : undefined);
    await completeLogin(login, bound ? guest : null);
  } catch (e) {
    if (e instanceof UpstreamError || e instanceof UpstreamProblem || e instanceof UpstreamValidation) {
      return seeOther(GOOGLE_ERROR_PATH);
    }
    throw e;
  }
  return seeOther(await takeNext());
}
