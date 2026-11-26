import "server-only";
import { cfg } from "@/lib/config";
import { baseUrl, callJson, errorFor, networkError, upstreamHeaders } from "@/lib/http";
import type { Topic } from "@/lib/identity";

// Types mirror the /tuvi/qa/** part of contracts/openapi/reading-tuvi.yaml.
export type QaKind = "PAID" | "FREE";
export type QaStatus = "PENDING" | "ANSWERED" | "RETRYING" | "REFUND_PENDING" | "REFUNDED" | "REFUSED" | "FAILED";
export type AskBody = { reading_id: string; kind: QaKind; topic: Topic; question: string; lang: "vi" };
export type FollowUpBody = { message_id: string; question: string };
export type QaReadingSummary = {
  reading_id: string;
  kind: QaKind;
  topic: Topic;
  status: QaStatus;
  created_at: string;
  follow_ups_left: number;
};
export type QaMessage = { seq: number; question: string; answer: string | null; created_at: string };
export type QaReading = QaReadingSummary & {
  retry_until: string | null;
  notice: string | null;
  disclaimer: string;
  messages: QaMessage[];
};
export type FreeQuota = { limit: number; used: number; remaining: number };
export type MergeResult = { moved: number };

async function stream(path: string, bearer: string, body: unknown, signal: AbortSignal): Promise<Response> {
  let res: Response;
  try {
    res = await fetch(`${baseUrl("reading")}${path}`, {
      method: "POST",
      headers: upstreamHeaders({ bearer, json: true, accept: "text/event-stream, application/problem+json" }),
      body: JSON.stringify(body),
      signal: AbortSignal.any([signal, AbortSignal.timeout(cfg().qaStreamTimeoutMs)]),
      cache: "no-store",
      redirect: "manual",
    });
  } catch (e) {
    throw networkError("reading", e);
  }
  if (res.status !== 200) throw await errorFor("reading", res);
  return res;
}

/** The raw SSE response of a question; throws UpstreamProblem / UpstreamValidation / UpstreamError otherwise. */
export function askStream(bearer: string, body: AskBody, signal: AbortSignal): Promise<Response> {
  return stream("/tuvi/qa/readings", bearer, body, signal);
}
export function followUpStream(bearer: string, id: string, body: FollowUpBody, signal: AbortSignal): Promise<Response> {
  return stream(`/tuvi/qa/readings/${encodeURIComponent(id)}/follow-ups`, bearer, body, signal);
}

export function listReadings(bearer: string): Promise<{ readings: QaReadingSummary[] }> {
  return callJson<{ readings: QaReadingSummary[] }>("reading", "/tuvi/qa/readings", { bearer });
}
export function getReading(bearer: string, id: string): Promise<QaReading> {
  return callJson<QaReading>("reading", `/tuvi/qa/readings/${encodeURIComponent(id)}`, { bearer });
}
export function deleteReadings(bearer: string): Promise<void> {
  return callJson<void>("reading", "/tuvi/qa/readings", { method: "DELETE", bearer });
}
export function freeQuota(bearer: string): Promise<FreeQuota> {
  return callJson<FreeQuota>("reading", "/tuvi/qa/free-quota", { bearer });
}
/** Moves a guest's Q&A history to the account it logged into (internal token only). */
export function mergeGuest(guestId: string, accountId: string): Promise<MergeResult> {
  return callJson<MergeResult>("reading", "/internal/qa/merge", {
    method: "POST",
    body: { guest_id: guestId, account_id: accountId },
  });
}
