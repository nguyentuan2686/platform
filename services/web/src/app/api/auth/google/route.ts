import { googleAuthorization } from "@/lib/identity";
import { GOOGLE_ERROR_PATH, googleRedirectUri, notFound, seeOther } from "@/lib/googleLogin";
import { cfg } from "@/lib/config";
import { rememberNext, setOidcState } from "@/lib/loginCookies";
import { assertSameOrigin, forbidden, ForbiddenOrigin } from "@/lib/sameOrigin";
import { loginGuest } from "@/lib/session";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

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
    /* no form body: default landing page */
  }

  let url: URL;
  try {
    const guest = await loginGuest({ create: true });
    const auth = await googleAuthorization(googleRedirectUri(), guest?.token);
    url = new URL(auth.authorization_url);
    if (url.protocol !== "https:") return seeOther(GOOGLE_ERROR_PATH);
    await setOidcState(auth.state);
  } catch (e) {
    if (e instanceof UpstreamError || e instanceof UpstreamProblem || e instanceof UpstreamValidation) {
      return seeOther(GOOGLE_ERROR_PATH);
    }
    if (e instanceof TypeError && /URL/i.test(e.message)) return seeOther(GOOGLE_ERROR_PATH);
    throw e;
  }
  await rememberNext(next);
  return seeOther(url.toString());
}
