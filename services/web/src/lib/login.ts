import "server-only";
import type { LoginSession } from "@/lib/identity";
import { log } from "@/lib/log";
import { mergeGuest } from "@/lib/qa";
import type { LoginGuest } from "@/lib/session";
import { setSessionCookie } from "@/lib/session";

/**
 * After identity accepted a login: the cookie is replaced by the new account session (no session
 * fixation), then the guest's Q&A history is moved to the account. identity merged its own guest
 * data during verification; reading-tuvi's merge is idempotent, so a failure is logged (ids only)
 * and the login goes on.
 */
export async function completeLogin(login: LoginSession, guest: LoginGuest | null): Promise<void> {
  await setSessionCookie(login.session_token, login.expires_at);
  if (!guest?.guestId) return;
  try {
    await mergeGuest(guest.guestId, login.account_id);
  } catch {
    log("warn", "qa merge failed", { guest_id: guest.guestId, account_id: login.account_id });
  }
}
