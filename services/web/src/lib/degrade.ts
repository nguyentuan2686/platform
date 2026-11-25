import "server-only";
import { UpstreamError, UpstreamProblem } from "@/lib/http";
import { log } from "@/lib/log";

/**
 * For optional page parts (header chip, "saved chart" link) that may degrade when identity is
 * unavailable. Returns normally when degrading is fine, throws otherwise:
 * - UpstreamError: down/timeout/5xx, already logged by http.ts → degrade silently;
 * - UpstreamProblem 401: a revoked or expired session → anonymous, not an error;
 * - other UpstreamProblem (403/404/409, e.g. a wrong internal token) → logged, then degrade;
 * - anything else (a bug) → rethrown, so the error boundary shows it and Next logs it.
 * Logs carry only the upstream name, status, reason code and the page part: no tokens, no birth.
 */
export function degradeOrThrow(e: unknown, part: string): void {
  if (e instanceof UpstreamError) return;
  if (e instanceof UpstreamProblem) {
    if (e.status === 401) return;
    const fields: Record<string, string> = { upstream: e.upstream ?? "unknown", status: String(e.status), part };
    if (e.reason) fields.reason = e.reason;
    log("warn", "upstream refused; degrading", fields);
    return;
  }
  throw e;
}
