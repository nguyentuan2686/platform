/** Where to land after login when nothing (or nothing safe) was asked for. */
export const DEFAULT_NEXT = "/vi/la-so";

// Lowercase path segments under /vi only: no scheme, no `//`, no `..`, no backslash, no query or hash.
const SAFE = /^\/vi(\/[a-z0-9-]+)*\/?$/;

/** An on-site post-login path: `raw` when it is a plain /vi path, else /vi/la-so (no open redirect). */
export function safeNext(raw: string | null | undefined): string {
  return typeof raw === "string" && SAFE.test(raw) ? raw : DEFAULT_NEXT;
}
