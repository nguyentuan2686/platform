import { googleAuthorization } from "@/lib/identity";
import { GOOGLE_ERROR_PATH, googleRedirectUri, notFound, seeOther } from "@/lib/googleLogin";
import { cfg } from "@/lib/config";
import { bindLoginToSession, rememberNext, setOidcState } from "@/lib/loginCookies";
import { assertSameOrigin, forbidden, ForbiddenOrigin } from "@/lib/sameOrigin";
import { loginGuest, readSessionToken } from "@/lib/session";
import { logRefusalOrThrow } from "@/lib/degrade";
import { log } from "@/lib/log";

export const dynamic = "force-dynamic";

/**
 * Starts Google login (the "Tiếp tục với Google" form). identity-svc owns state, nonce and PKCE;
 * the web keeps the state in a short cookie scoped to the callback, to bind it to this browser.
 */
export async function POST(req: Request): Promise<Response> {
  try {
    assertSameOrigin(req);
  } catch (e) {
    if (e instanceof ForbiddenOrigin) return forbidden();
    throw e;
  }
  if (!cfg().googleLogin) return notFound();

  let next: string | null = null;
  try {
    const v = (await req.formData()).get("next");
    next = typeof v === "string" ? v : null;
  } catch {
    /* catch-ok: no form body, so the default landing page */
  }

  let url: URL;
  let session: string | undefined;
  try {
    const guest = await loginGuest({ create: true });
    const auth = await googleAuthorization(googleRedirectUri(), guest?.token);
    url = new URL(auth.authorization_url);
    if (url.protocol !== "https:") {
      log("error", "google authorization url refused", { upstream: "identity-svc", reason: "not_https" });
      return seeOther(GOOGLE_ERROR_PATH);
    }
    await setOidcState(auth.state);
    session = guest?.token ?? (await readSessionToken());
  } catch (e) {
    if (e instanceof TypeError && /URL/i.test(e.message)) {
      log("error", "google authorization url refused", { upstream: "identity-svc", reason: "unparsable" });
      return seeOther(GOOGLE_ERROR_PATH);
    }
    logRefusalOrThrow(e, "google-start"); // down: logged by http.ts; any refusal: logged here
    return seeOther(GOOGLE_ERROR_PATH);
  }
  if (session) await bindLoginToSession(session);
  await rememberNext(next);
  return seeOther(url.toString());
}
