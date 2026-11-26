import "server-only";
import { callJson } from "@/lib/http";
import type { Birth, BasicReading, Chart, TodayReading } from "@/lib/types";

export { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

// The birth travels only in request bodies; callJson never logs bodies.
export function getChart(b: Birth): Promise<Chart> {
  return callJson<Chart>("chart", "/tuvi/chart", { method: "POST", body: b });
}
export function getBasicReading(b: Birth): Promise<BasicReading> {
  return callJson<BasicReading>("reading", "/tuvi/readings/basic", { method: "POST", body: b });
}
export function getToday(b: Birth, date: string): Promise<TodayReading> {
  return callJson<TodayReading>("reading", "/tuvi/readings/today", { method: "POST", body: { ...b, date } });
}
