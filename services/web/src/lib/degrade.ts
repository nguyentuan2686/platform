import "server-only";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";
import { log, type Level } from "@/lib/log";

/**
 * Logs an upstream refusal (UpstreamProblem / UpstreamValidation) with the upstream name, status,
 * reason code, the rejected field names and the page part only: never bodies, tokens, emails,
 * birth or bank data. UpstreamError was already logged by http.ts. Anything else (a bug) rethrows.
 */
function refusal(e: unknown, part: string, level: Level, message: string): void {
  if (e instanceof UpstreamError) return;
  if (e instanceof UpstreamProblem) {
    const fields: Record<string, string> = { upstream: e.upstream ?? "unknown", status: String(e.status), part };
    if (e.reason) fields.reason = e.reason;
    log(level, message, fields);
    return;
  }
  if (e instanceof UpstreamValidation) {
    const fields: Record<string, string> = { upstream: e.upstream ?? "unknown", status: "400", part };
    const names = Object.keys(e.fields).filter((f) => /^[A-Za-z0-9_.[\]-]{1,64}$/.test(f));
    if (names.length) fields.fields = names.join(",");
    log(level, message, fields);
    return;
  }
  throw e;
}

/**
 * For optional page parts (header chip, "saved chart" link) that may degrade when identity is
 * unavailable. Returns normally when degrading is fine, throws otherwise:
 * - UpstreamError: down/timeout/5xx, already logged by http.ts → degrade silently;
 * - UpstreamProblem 401: a revoked or expired session → anonymous, not an error;
 * - other UpstreamProblem (403/404/409, e.g. a wrong internal token) or a 400 → logged, then degrade;
 * - anything else (a bug) → rethrown, so the error boundary shows it and Next logs it.
 */
export function degradeOrThrow(e: unknown, part: string): void {
  if (e instanceof UpstreamProblem && e.status === 401) return;
  refusal(e, part, "warn", "upstream refused; degrading");
}

/**
 * For a request the UI answers with a message ("thử lại sau"): the caller first handles the
 * refusals it expects (a 409 it explains, a 400 on what the user typed); everything left is
 * unexpected (a 401/403 from a wrong token, an unknown 4xx, a contract mismatch) and is logged
 * at error level here before the caller degrades. UpstreamError: already logged. A bug rethrows.
 */
export function logRefusalOrThrow(e: unknown, part: string): void {
  refusal(e, part, "error", "upstream refused request");
}
