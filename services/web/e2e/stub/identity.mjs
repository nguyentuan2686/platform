// identity-svc stub: guests, accounts (magic links), /me, entitlements and the chart profile, in memory.
// When STUB_INTERNAL_TOKEN is set, every service call must carry it in X-Internal-Token (else 401).
// Test control: GET /__last-magic-token → {token} (the link a real mail would carry).
import { randomBytes, randomUUID } from "node:crypto";

export const NAME = "identity-svc";
const GUEST_TTL_MS = 180 * 24 * 3600 * 1000;
const ACCOUNT_TTL_MS = 30 * 24 * 3600 * 1000;

/** token -> { kind: "guest", guest_id } | { kind: "account", account_id, email_masked } */
const sessions = new Map();
/** subject id (guest_id or account_id) -> profile */
const profiles = new Map();
/** email -> { account_id, credits } */
const accounts = new Map();
/** magic token -> { email, used, guest_id } (the guest that asked, merged at verify like identity-svc) */
const links = new Map();
let lastMagicToken = null;

export function reset() {
  sessions.clear();
  profiles.clear();
  accounts.clear();
  links.clear();
  lastMagicToken = null;
}

const findAccount = (accountId) => [...accounts.values()].find((a) => a.account_id === accountId);

const PASS_DAYS = { PASS_MONTH: ["MONTH", 30, 2], PASS_YEAR: ["YEAR", 365, null] };

/** payment-svc's PaymentSucceeded, applied in process (the payment stub calls it): credits or a pass. */
export function grant(accountId, productCode) {
  const acc = findAccount(accountId);
  if (!acc) return;
  const now = new Date();
  const credits = /^CREDIT_(\d+)$/.exec(productCode);
  if (credits) {
    const n = Number(credits[1]);
    acc.credits += n;
    acc.ledger.push({ delta: n, source: "CREDIT", reason: "purchase", topic: null, created_at: now.toISOString() });
    return;
  }
  const [kind, days, questions] = PASS_DAYS[productCode] ?? [];
  if (kind) acc.pass = { kind, ends_at: new Date(now.getTime() + days * 86400_000).toISOString(), questions_left: questions };
}

/** A refunded credit pack takes its credits back; false when some were already used. */
export function reverse(accountId, productCode) {
  const acc = findAccount(accountId);
  const n = Number(/^CREDIT_(\d+)$/.exec(productCode)?.[1] ?? 0);
  if (!acc || acc.credits < n) return false;
  acc.credits -= n;
  acc.ledger.push({ delta: -n, source: "CREDIT", reason: "reversal", topic: null, created_at: new Date().toISOString() });
  return true;
}

const problem = (status, extra = {}) => ({ type: "urn:problem:stub", title: "stub", status, trace_id: "", ...extra });
const newToken = () => randomBytes(32).toString("base64url");
const subjectId = (who) => (who.kind === "guest" ? who.guest_id : who.account_id);
const mask = (email) => {
  const [user, domain] = email.split("@");
  const dot = domain.lastIndexOf(".");
  return `${user[0]}***@${domain[0]}***${dot > 0 ? domain.slice(dot) : ""}`;
};

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

function verify(req, res, body, send) {
  const link = typeof body?.token === "string" ? links.get(body.token) : undefined;
  if (!link || link.used) {
    send(res, 401, problem(401), "application/problem+json");
    return;
  }
  link.used = true;
  let account = accounts.get(link.email);
  const created = !account;
  if (!account) {
    // signup bonus
    account = {
      account_id: randomUUID(),
      credits: 1,
      pass: null,
      ledger: [{ delta: 1, source: "CREDIT", reason: "signup_bonus", topic: null, created_at: new Date().toISOString() }],
    };
    accounts.set(link.email, account);
  }
  // The guest bearer at verify, else the guest that asked for the link: its profile follows the user
  // into the account (when the account has none), as identity-svc does.
  const guestToken = bearer(req);
  const guest = guestToken ? sessions.get(guestToken) : undefined;
  const guestId = guest?.kind === "guest" ? guest.guest_id : link.guest_id;
  if (guestId) {
    const p = profiles.get(guestId);
    if (p && !profiles.has(account.account_id)) profiles.set(account.account_id, p);
    profiles.delete(guestId);
  }
  if (guest?.kind === "guest") sessions.delete(guestToken);
  const token = newToken();
  sessions.set(token, { kind: "account", account_id: account.account_id, email_masked: mask(link.email) });
  send(res, 200, {
    session_token: token,
    account_id: account.account_id,
    expires_at: new Date(Date.now() + ACCOUNT_TTL_MS).toISOString(),
    created,
  });
}

export function handle(req, res, body, send) {
  const route = `${req.method} ${(req.url ?? "").split("?")[0]}`;
  if (route === "GET /__last-magic-token") {
    send(res, 200, { token: lastMagicToken });
    return true;
  }
  const want = process.env.STUB_INTERNAL_TOKEN;
  if (want && req.headers["x-internal-token"] !== want) {
    send(res, 401, problem(401), "application/problem+json");
    return true;
  }
  if (route === "POST /guests") {
    const token = newToken();
    const guest_id = randomUUID();
    sessions.set(token, { kind: "guest", guest_id });
    send(res, 201, { session_token: token, guest_id, expires_at: new Date(Date.now() + GUEST_TTL_MS).toISOString() });
    return true;
  }
  if (route === "POST /auth/magic-links") {
    const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : "";
    if (!/^[^@\s]+@[^@\s]+$/.test(email)) {
      send(res, 400, problem(400, { errors: [{ field: "email", message: "invalid" }] }), "application/problem+json");
      return true;
    }
    lastMagicToken = newToken();
    const asker = sessions.get(bearer(req) ?? "");
    links.set(lastMagicToken, { email, used: false, guest_id: asker?.kind === "guest" ? asker.guest_id : null });
    send(res, 202, { status: "sent_if_allowed" });
    return true;
  }
  if (route === "POST /auth/magic-links/verify") {
    verify(req, res, body, send);
    return true;
  }
  if (route.startsWith("GET /auth/google/") || route === "POST /auth/google/callback") {
    send(res, 503, problem(503), "application/problem+json"); // Google login not configured
    return true;
  }
  if (!route.includes(" /me") && route !== "DELETE /sessions/current") return false;

  const token = bearer(req);
  const who = token ? sessions.get(token) : undefined;
  if (!who) {
    send(res, 401, problem(401), "application/problem+json");
    return true;
  }
  const sid = subjectId(who);
  switch (route) {
    case "DELETE /sessions/current":
      sessions.delete(token);
      send(res, 204);
      return true;
    case "GET /me":
      send(res, 200, who);
      return true;
    case "GET /me/profile": {
      const p = profiles.get(sid);
      if (p) send(res, 200, p);
      else send(res, 404, problem(404), "application/problem+json");
      return true;
    }
    case "PUT /me/profile": {
      if (!validProfile(body)) {
        send(res, 400, problem(400, { errors: [{ field: "birth", message: "invalid" }] }), "application/problem+json");
        return true;
      }
      const old = profiles.get(sid);
      const birth = { zone: "Asia/Ho_Chi_Minh", ...body.birth };
      if (old?.locked && JSON.stringify(old.birth) !== JSON.stringify(birth)) {
        send(res, 409, problem(409, { reason: "profile_locked" }), "application/problem+json");
        return true;
      }
      const p = { id: old?.id ?? randomUUID(), label: body.label, birth, calendar: body.calendar, locked: old?.locked ?? false };
      profiles.set(sid, p);
      send(res, 200, p);
      return true;
    }
    case "DELETE /me/profile":
      profiles.delete(sid);
      send(res, 204);
      return true;
    case "GET /me/entitlements": {
      if (who.kind !== "account") {
        send(res, 403, problem(403), "application/problem+json");
        return true;
      }
      const acc = [...accounts.values()].find((a) => a.account_id === who.account_id);
      send(res, 200, { credits: acc?.credits ?? 0, pass: acc?.pass ?? null, features: [] });
      return true;
    }
    case "GET /me/ledger": {
      const acc = who.kind === "account" ? findAccount(who.account_id) : undefined;
      if (!acc) send(res, 403, problem(403), "application/problem+json");
      else send(res, 200, [...acc.ledger].reverse()); // newest first
      return true;
    }
    default:
      return false;
  }
}
