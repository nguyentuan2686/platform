import { createSseParser } from "@/lib/sse";

/** The frames of reading-tuvi's Q&A stream that the UI reacts to (client-safe). */
export type QaEvent =
  | { type: "meta"; followUpsLeft: number | null }
  | { type: "delta"; text: string }
  | { type: "done"; disclaimer: string }
  | { type: "refused"; text: string; disclaimer: string }
  | { type: "queued"; text: string }
  | { type: "busy"; text: string }
  | { type: "error" };

export type QaOutcome =
  /** The stream ended with a terminal frame (done, refused, queued, busy or error). */
  | { kind: "complete" }
  /** The route refused before streaming: HTTP status and the problem's reason. */
  | { kind: "problem"; status: number; reason: string }
  /** The connection broke, or the stream ended without a terminal frame. */
  | { kind: "dropped" };

const str = (v: unknown): string => (typeof v === "string" ? v : "");
const obj = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null ? (v as Record<string, unknown>) : {});

function toEvent(name: string, data: unknown): QaEvent | null {
  const d = obj(data);
  switch (name) {
    case "meta":
      return { type: "meta", followUpsLeft: typeof d.follow_ups_left === "number" ? d.follow_ups_left : null };
    case "delta":
      return str(d.text) ? { type: "delta", text: str(d.text) } : null;
    case "done":
      return { type: "done", disclaimer: str(d.disclaimer) };
    case "refused":
      return { type: "refused", text: str(d.text), disclaimer: str(d.disclaimer) };
    case "queued":
      return { type: "queued", text: str(d.text) };
    case "busy":
      return { type: "busy", text: str(d.text) };
    case "error":
      return { type: "error" };
    default:
      return null;
  }
}

const TERMINAL = new Set<QaEvent["type"]>(["done", "refused", "queued", "busy", "error"]);

async function reasonOf(res: Response): Promise<string> {
  try {
    const p = (await res.json()) as { reason?: unknown };
    if (typeof p.reason === "string") return p.reason;
  } catch {
    /* unreadable body */
  }
  return res.status === 401 ? "unauthenticated" : "unavailable";
}

/**
 * POSTs `body` as JSON to a BFF Q&A route and feeds the SSE answer to `onEvent` as it arrives
 * (bytes are decoded as a stream, so a multibyte character split between chunks stays intact).
 * Never throws: a failure is the returned outcome.
 */
export async function postQaStream(
  url: string,
  body: unknown,
  onEvent: (e: QaEvent) => void,
  signal?: AbortSignal,
): Promise<QaOutcome> {
  let res: Response;
  try {
    res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "text/event-stream" },
      body: JSON.stringify(body),
      signal,
      cache: "no-store",
    });
  } catch {
    return { kind: "dropped" };
  }
  if (!res.ok || !res.body) {
    return res.ok ? { kind: "dropped" } : { kind: "problem", status: res.status, reason: await reasonOf(res) };
  }
  let terminal = false;
  const parser = createSseParser(({ event, data }) => {
    const e = toEvent(event, data);
    if (!e) return;
    if (TERMINAL.has(e.type)) terminal = true;
    onEvent(e);
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    parser.end();
  } catch {
    parser.end();
    return { kind: "dropped" };
  }
  return terminal ? { kind: "complete" } : { kind: "dropped" };
}
