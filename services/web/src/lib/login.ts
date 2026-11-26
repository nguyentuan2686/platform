import "server-only";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";
import type { LoginSession } from "@/lib/identity";
import { log } from "@/lib/log";
import { mergeGuest } from "@/lib/qa";
import type { LoginGuest } from "@/lib/session";
import { setSessionCookie } from "@/lib/session";

/**
 * After identity accepted a login: the cookie is replaced by the new account session (no session
 * fixation), then the guest's Q&A history is moved to the account. identity merged its own guest
 * data during verification; reading-tuvi's merge is idempotent, so an upstream failure is logged
 * (ids, upstream and status only) and the login goes on. A bug rethrows.
 */
export async function completeLogin(login: LoginSession, guest: LoginGuest | null): Promise<void> {
  await setSessionCookie(login.session_token, login.expires_at);
  if (!guest?.guestId) return;
  try {
    await mergeGuest(guest.guestId, login.account_id);
  } catch (e) {
    if (!(e instanceof UpstreamError || e instanceof UpstreamProblem || e instanceof UpstreamValidation)) throw e;
    const status = e instanceof UpstreamError ? "unavailable" : e instanceof UpstreamProblem ? String(e.status) : "400";
    log("warn", "qa merge failed", {
      guest_id: guest.guestId,
      account_id: login.account_id,
      upstream: "reading-tuvi",
      status,
    });
  }
}
