import { log } from "@/lib/log";
import type { Birth, BasicReading, Chart, TodayReading } from "@/lib/types";

export class UpstreamError extends Error {
  constructor(readonly kind: "unavailable" = "unavailable") {
    super(kind);
    this.name = "UpstreamError";
  }
}
/** Upstream rejected the birth data; `fields` maps upstream field name -> message. */
export class UpstreamValidation extends Error {
  constructor(readonly fields: Record<string, string>) {
    super("validation");
    this.name = "UpstreamValidation";
  }
}

const TIMEOUT_MS = 5000;
const chartUrl = () => process.env.CHART_SVC_URL ?? "http://localhost:8082";
const readingUrl = () => process.env.READING_URL ?? "http://localhost:8083";

async function post<T>(name: string, base: string, path: string, body: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${base}${path}`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json, application/problem+json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
  } catch (e) {
    // Never log the request body (birth data) or the raw error message.
    log("error", "upstream unreachable", { upstream: name, reason: e instanceof Error ? e.name : "unknown" });
    throw new UpstreamError("unavailable");
  }
  if (res.status === 400) {
    const fields: Record<string, string> = {};
    try {
      const p = (await res.json()) as { errors?: { field: string; message: string }[] };
      for (const er of p.errors ?? []) fields[er.field] ??= er.message;
    } catch {
      /* body unreadable: no field detail */
    }
    throw new UpstreamValidation(fields);
  }
  if (!res.ok) {
    log("error", "upstream error", { upstream: name, status: String(res.status) });
    throw new UpstreamError("unavailable");
  }
  try {
    return (await res.json()) as T;
  } catch {
    log("error", "upstream bad body", { upstream: name });
    throw new UpstreamError("unavailable");
  }
}

export function getChart(b: Birth): Promise<Chart> {
  return post<Chart>("chart-svc", chartUrl(), "/tuvi/chart", b);
}
export function getBasicReading(b: Birth): Promise<BasicReading> {
  return post<BasicReading>("reading-tuvi", readingUrl(), "/tuvi/readings/basic", b);
}
export function getToday(b: Birth, date: string): Promise<TodayReading> {
  return post<TodayReading>("reading-tuvi", readingUrl(), "/tuvi/readings/today", { ...b, date });
}
