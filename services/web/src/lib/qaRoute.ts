import "server-only";
import { logRefusalOrThrow } from "@/lib/degrade";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";
import { assertSameOrigin, forbidden, ForbiddenOrigin } from "@/lib/sameOrigin";

/** Name of the cookie that records the AI-provider consent (HttpOnly, one year). */
export const AI_CONSENT_COOKIE = "tv_ai_ok";

export const reply = (status: number, body: unknown): Response =>
  Response.json(body, { status, headers: { "cache-control": "no-store" } });

/** Same-origin guard for a state-changing handler; the 403 to send, or null when the request may go on. */
export function csrfRefusal(req: Request): Response | null {
  try {
    assertSameOrigin(req);
    return null;
  } catch (e) {
    if (e instanceof ForbiddenOrigin) return forbidden();
    throw e;
  }
}

/** The JSON object of a request body, or null when it is unreadable or not an object. */
export async function readObject(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const v: unknown = await req.json();
    return typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    // catch-ok: an unparsable body is the client's error; the caller answers 400 "invalid"
    return null;
  }
}

/** A Q&A question: 5 to 500 characters, as reading-tuvi checks it. */
export const isQuestion = (q: unknown): q is string => typeof q === "string" && q.length >= 5 && q.length <= 500;

/** The upstream SSE passed through untouched (no buffering, no transform). */
export function sseResponse(upstream: Response): Response {
  return new Response(upstream.body, {
    status: 200,
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      "x-accel-buffering": "no",
    },
  });
}

const FALLBACK_REASON: Record<number, string> = {
  401: "unauthenticated",
  403: "forbidden",
  404: "not_found",
  409: "conflict",
};

/**
 * The route's answer to a failed upstream call, as `{reason}` with the upstream's status:
 * down/5xx → 503 "unavailable"; 409 and 403 account_required are expected and pass quietly;
 * everything else (a 401/403 from a wrong token, a 400 on our own request) is logged first
 * (names only, never the question). A bug rethrows.
 */
export function upstreamFailure(e: unknown, part: string): Response {
  if (e instanceof UpstreamError) return reply(503, { reason: "unavailable" });
  if (e instanceof UpstreamProblem) {
    const expected = e.status === 409 || (e.status === 403 && e.reason === "account_required");
    if (!expected) logRefusalOrThrow(e, part);
    return reply(e.status, { reason: e.reason ?? FALLBACK_REASON[e.status] ?? "forbidden" });
  }
  if (e instanceof UpstreamValidation) {
    logRefusalOrThrow(e, part);
    const field = Object.keys(e.fields).find((f) => /^[a-z_]{1,32}$/.test(f));
    return reply(400, field ? { reason: "invalid", field } : { reason: "invalid" });
  }
  throw e;
}
