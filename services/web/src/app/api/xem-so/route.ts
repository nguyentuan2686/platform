import { clientIp } from "@/lib/clientIp";
import { UpstreamProblem, UpstreamValidation } from "@/lib/http";
import { checkNumber, numberQuota, type NumberCheckInput, type NumberQuota } from "@/lib/numberCheck";
import { csrfRefusal, readObject, reply, upstreamFailure } from "@/lib/qaRoute";
import { ensureSession } from "@/lib/session";
import { logRefusalOrThrow } from "@/lib/degrade";

export const dynamic = "force-dynamic";

const invalid = (field: string) => reply(400, { reason: "invalid", field });
const KINDS = ["sim", "bank", "plate"] as const;
const VEHICLES = ["car", "motorbike"] as const;
// reading-tuvi reports a bad number or birth year on these fields: the user's typing, not a bug.
const USER_FIELDS = new Set(["number", "birth_year"]);

/**
 * "Xem số hợp mệnh": forwards one number check to reading-tuvi and answers its JSON. The number
 * travels only in the body, is never logged, never put in a URL and never cached (no-store).
 * A visitor without a session gets a guest; the end user's IP feeds reading-tuvi's per-IP limit.
 */
export async function POST(req: Request): Promise<Response> {
  const refusal = csrfRefusal(req);
  if (refusal) return refusal;
  const b = await readObject(req);
  if (!b) return invalid("body");
  const kind = KINDS.find((k) => k === b.kind);
  if (!kind) return invalid("kind");
  const vehicle = VEHICLES.find((v) => v === b.vehicle);
  if (kind === "plate" && !vehicle) return invalid("vehicle");
  if (typeof b.number !== "string" || b.number.trim().length === 0 || b.number.length > 64) return invalid("number");
  if (typeof b.birthYear !== "number" || !Number.isInteger(b.birthYear)) return invalid("birthYear");
  if (b.beforeTet !== undefined && typeof b.beforeTet !== "boolean") return invalid("beforeTet");
  if (b.gender !== "male" && b.gender !== "female") return invalid("gender");
  const input: NumberCheckInput = {
    kind,
    ...(kind === "plate" ? { vehicle } : {}),
    number: b.number,
    birthYear: b.birthYear,
    beforeTet: b.beforeTet === true,
    gender: b.gender,
  };

  let token: string | undefined;
  try {
    token = await ensureSession();
    return reply(200, await checkNumber(input, token, await clientIp()));
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 409 && e.reason === "number_quota") {
      return reply(409, { reason: "number_quota", quota: await quotaOrNull(token) });
    }
    if (e instanceof UpstreamValidation) {
      const field = Object.keys(e.fields).find((f) => USER_FIELDS.has(f));
      if (field) return invalid(field === "birth_year" ? "birthYear" : field);
    }
    return upstreamFailure(e, "xem-so");
  }
}

/** Best effort: the quota for the 409 body; null when it cannot be read (the message does not need it). */
async function quotaOrNull(token: string | undefined): Promise<NumberQuota | null> {
  if (!token) return null;
  try {
    return await numberQuota(token);
  } catch (e) {
    logRefusalOrThrow(e, "xem-so-quota");
    return null;
  }
}
