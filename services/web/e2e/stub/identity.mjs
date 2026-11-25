// identity-svc stub: guests, /me and the chart profile, kept in memory per bearer.
// When STUB_INTERNAL_TOKEN is set, every call must carry it in X-Internal-Token (else 401).
import { randomBytes, randomUUID } from "node:crypto";

export const NAME = "identity-svc";
const GUEST_TTL_MS = 180 * 24 * 3600 * 1000;

/** token -> { kind: "guest", guest_id } */
const sessions = new Map();
/** token -> profile */
const profiles = new Map();

export function reset() {
  sessions.clear();
  profiles.clear();
}

const problem = (status, extra = {}) => ({ type: "urn:problem:stub", title: "stub", status, trace_id: "", ...extra });

function bearer(req) {
  const h = req.headers.authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
}

function validProfile(b) {
  return (
    b &&
    typeof b.label === "string" &&
    b.label.length >= 1 &&
    b.label.length <= 40 &&
    (b.calendar === "VN" || b.calendar === "CN") &&
    b.birth &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(b.birth.birth ?? "") &&
    (b.birth.gender === "male" || b.birth.gender === "female")
  );
}

export function handle(req, res, body, send) {
  const want = process.env.STUB_INTERNAL_TOKEN;
  if (want && req.headers["x-internal-token"] !== want) {
    send(res, 401, problem(401), "application/problem+json");
    return true;
  }
  const route = `${req.method} ${(req.url ?? "").split("?")[0]}`;
  if (route === "POST /guests") {
    const token = randomBytes(32).toString("base64url");
    const guest_id = randomUUID();
    sessions.set(token, { kind: "guest", guest_id });
    send(res, 201, { session_token: token, guest_id, expires_at: new Date(Date.now() + GUEST_TTL_MS).toISOString() });
    return true;
  }
  if (!route.includes(" /me")) return false;

  const token = bearer(req);
  const who = token ? sessions.get(token) : undefined;
  if (!who) {
    send(res, 401, problem(401), "application/problem+json");
    return true;
  }
  switch (route) {
    case "GET /me":
      send(res, 200, who);
      return true;
    case "GET /me/profile": {
      const p = profiles.get(token);
      if (p) send(res, 200, p);
      else send(res, 404, problem(404), "application/problem+json");
      return true;
    }
    case "PUT /me/profile": {
      if (!validProfile(body)) {
        send(res, 400, problem(400, { errors: [{ field: "birth", message: "invalid" }] }), "application/problem+json");
        return true;
      }
      const old = profiles.get(token);
      const birth = { zone: "Asia/Ho_Chi_Minh", ...body.birth };
      if (old?.locked && JSON.stringify(old.birth) !== JSON.stringify(birth)) {
        send(res, 409, problem(409, { reason: "profile_locked" }), "application/problem+json");
        return true;
      }
      const p = { id: old?.id ?? randomUUID(), label: body.label, birth, calendar: body.calendar, locked: old?.locked ?? false };
      profiles.set(token, p);
      send(res, 200, p);
      return true;
    }
    case "DELETE /me/profile":
      profiles.delete(token);
      send(res, 204);
      return true;
    case "GET /me/entitlements":
    case "GET /me/ledger":
      send(res, 403, problem(403), "application/problem+json");
      return true;
    default:
      return false;
  }
}
