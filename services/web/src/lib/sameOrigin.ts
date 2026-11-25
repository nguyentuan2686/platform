import "server-only";
import { cfg } from "@/lib/config";

export class ForbiddenOrigin extends Error {
  constructor() {
    super("forbidden origin");
    this.name = "ForbiddenOrigin";
  }
}

/**
 * CSRF guard for state-changing route handlers (POST/DELETE under /api/**): the Origin must equal
 * WEB_ORIGIN; without an Origin, Sec-Fetch-Site must be same-origin. Anything else throws.
 */
export function assertSameOrigin(req: Request): void {
  const origin = req.headers.get("origin");
  if (origin !== null) {
    if (origin !== cfg().webOrigin) throw new ForbiddenOrigin();
    return;
  }
  if (req.headers.get("sec-fetch-site") !== "same-origin") throw new ForbiddenOrigin();
}

export function forbidden(): Response {
  return Response.json({ reason: "forbidden" }, { status: 403, headers: { "cache-control": "no-store" } });
}
