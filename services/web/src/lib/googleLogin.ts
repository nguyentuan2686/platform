import "server-only";
import { cfg } from "@/lib/config";

/** The Google callback route; the OIDC state cookie is scoped to it. */
export const OIDC_CALLBACK_PATH = "/vi/dang-nhap/google";

export const GOOGLE_ERROR_PATH = "/vi/dang-nhap?loi=google";

/** The redirect URI registered with identity-svc (OIDC_REDIRECT_URIS). */
export function googleRedirectUri(): string {
  return cfg().webOrigin + OIDC_CALLBACK_PATH;
}

/** 303 to a path of this site (absolute, on WEB_ORIGIN), never cached. */
export function seeOther(location: string): Response {
  const abs = location.startsWith("/") ? cfg().webOrigin + location : location;
  return new Response(null, { status: 303, headers: { location: abs, "cache-control": "no-store" } });
}

export function notFound(): Response {
  return new Response(null, { status: 404, headers: { "cache-control": "no-store" } });
}
