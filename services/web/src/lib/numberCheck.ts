import "server-only";
import { callJson } from "@/lib/http";

// Types mirror the /tuvi/number-check part of contracts/openapi/reading-tuvi.yaml.
export type NumberKind = "sim" | "bank" | "plate";
export type Vehicle = "car" | "motorbike";
export type Gender = "male" | "female";
export type NumberCheckInput = {
  kind: NumberKind;
  vehicle?: Vehicle;
  number: string;
  birthYear: number;
  beforeTet: boolean;
  gender: Gender;
};
export type NumberQuota = { limit: number; used: number; remaining: number };
export type NumberCriterion = { key: string; label: string; detail: string; points: number; max: number };
export type NumberCheckResult = {
  score: number;
  band: string;
  bandLabel: string;
  menh: { canChi: string; napAm: string; element: string };
  cungPhi: { name: string; group: string };
  criteria: NumberCriterion[];
  reading: string[];
  quota: NumberQuota;
};

/** Scores a number in reading-tuvi. The number travels only in the body; the client IP feeds the per-IP limit. */
export function checkNumber(input: NumberCheckInput, bearer: string, clientIp: string | undefined): Promise<NumberCheckResult> {
  return callJson<NumberCheckResult>("reading", "/tuvi/number-check", {
    method: "POST",
    bearer,
    body: input,
    headers: clientIp ? { "x-client-ip": clientIp } : undefined,
  });
}

export function numberQuota(bearer: string): Promise<NumberQuota> {
  return callJson<NumberQuota>("reading", "/tuvi/number-check/quota", { bearer });
}
