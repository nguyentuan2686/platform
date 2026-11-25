import "server-only";
import { cfg } from "@/lib/config";
import { log } from "@/lib/log";

/** Upstream down, timed out, answered 5xx (or an unexpected status) or sent an unreadable body. */
export class UpstreamError extends Error {
  constructor(readonly kind: "unavailable" = "unavailable") {
    super(kind);
    this.name = "UpstreamError";
  }
}
/** Upstream answered 400; `fields` maps the upstream field name to its first message. */
export class UpstreamValidation extends Error {
  constructor(readonly fields: Record<string, string>) {
    super("validation");
    this.name = "UpstreamValidation";
  }
}
/** Upstream answered 401, 403, 404 or 409; `reason` is the problem's snake_case `reason`, if any. */
export class UpstreamProblem extends Error {
  constructor(
    readonly status: 401 | 403 | 404 | 409,
    readonly reason?: string,
  ) {
    super(reason ? `upstream ${status} ${reason}` : `upstream ${status}`);
    this.name = "UpstreamProblem";
  }
}

export type Svc = "chart" | "reading" | "identity" | "payment";
export type CallOpts = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  bearer?: string;
  body?: unknown;
  admin?: boolean;
  timeoutMs?: number;
  /** Extra request headers (e.g. webhook authentication). Never logged. */
  headers?: Record<string, string>;
};

export const SVC_NAME: Record<Svc, string> = {
  chart: "chart-svc",
  reading: "reading-tuvi",
  identity: "identity-svc",
  payment: "payment-svc",
};

const JSON_TIMEOUT_MS = 5000;

export function baseUrl(svc: Svc): string {
  const c = cfg();
  return { chart: c.chartUrl, reading: c.readingUrl, identity: c.identityUrl, payment: c.paymentUrl }[svc];
}

/** Request headers for a service call. The browser's own headers and cookies are never forwarded. */
export function upstreamHeaders(opts: CallOpts & { accept?: string; json?: boolean }): Headers {
  const c = cfg();
  const h = new Headers({ accept: opts.accept ?? "application/json, application/problem+json" });
  if (opts.json) h.set("content-type", "application/json");
  if (c.internalToken) h.set("x-internal-token", c.internalToken);
  if (opts.bearer) h.set("authorization", `Bearer ${opts.bearer}`);
  if (opts.admin && c.adminToken) h.set("x-admin-token", c.adminToken);
  for (const [k, v] of Object.entries(opts.headers ?? {})) h.set(k, v);
  return h;
}

/** Only the upstream name and a status are ever logged: no path, query, body or headers. */
export function logUpstream(svc: Svc, status: string): void {
  log("error", "upstream failed", { upstream: SVC_NAME[svc], status });
}

/** Maps a non-2xx upstream response to the matching error (consumes the body). */
export async function errorFor(svc: Svc, res: Response): Promise<Error> {
  if (res.status === 400) {
    const fields: Record<string, string> = {};
    try {
      const p = (await res.json()) as { errors?: { field: string; message: string }[] };
      for (const er of p.errors ?? []) fields[er.field] ??= er.message;
    } catch {
      /* unreadable body: no field detail */
    }
    return new UpstreamValidation(fields);
  }
  if (res.status === 401 || res.status === 403 || res.status === 404 || res.status === 409) {
    let reason: string | undefined;
    try {
      const p = (await res.json()) as { reason?: unknown };
      if (typeof p.reason === "string" && /^[a-z0-9_]{1,64}$/.test(p.reason)) reason = p.reason;
    } catch {
      /* no body */
    }
    return new UpstreamProblem(res.status as UpstreamProblem["status"], reason);
  }
  logUpstream(svc, String(res.status));
  await res.body?.cancel().catch(() => {});
  return new UpstreamError("unavailable");
}

/** Fetch failure (network, timeout, abort) → logged and mapped to UpstreamError. */
export function networkError(svc: Svc, e: unknown): UpstreamError {
  const timeout = e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError");
  logUpstream(svc, timeout ? "timeout" : "unreachable");
  return new UpstreamError("unavailable");
}

export async function callJson<T>(svc: Svc, path: string, opts: CallOpts = {}): Promise<T> {
  const hasBody = opts.body !== undefined;
  let res: Response;
  try {
    res = await fetch(`${baseUrl(svc)}${path}`, {
      method: opts.method ?? "GET",
      headers: upstreamHeaders({ ...opts, json: hasBody }),
      body: hasBody ? JSON.stringify(opts.body) : undefined,
      signal: AbortSignal.timeout(opts.timeoutMs ?? JSON_TIMEOUT_MS),
      cache: "no-store",
      redirect: "manual",
    });
  } catch (e) {
    throw networkError(svc, e);
  }
  if (res.status === 204) return undefined as T;
  if (!res.ok) throw await errorFor(svc, res);
  try {
    return (await res.json()) as T;
  } catch {
    logUpstream(svc, "bad_body");
    throw new UpstreamError("unavailable");
  }
}
