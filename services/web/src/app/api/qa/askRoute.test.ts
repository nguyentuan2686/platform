import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const session = vi.hoisted(() => ({
  ensureSession: vi.fn(),
  getViewer: vi.fn(),
  readSessionToken: vi.fn(),
}));
vi.mock("@/lib/session", () => session);
const jar = vi.hoisted(() => ({ values: new Map<string, string>(), sets: [] as unknown[][] }));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (n: string) => (jar.values.has(n) ? { name: n, value: jar.values.get(n) } : undefined),
    set: (...a: unknown[]) => void jar.sets.push(a),
  }),
}));

import { POST as ask } from "@/app/api/qa/readings/route";
import { POST as followUp } from "@/app/api/qa/readings/[id]/follow-ups/route";
import { GET as status } from "@/app/api/qa/readings/[id]/route";

const ORIGIN = "http://localhost:3001";
const RID = "33333333-3333-4333-8333-333333333333";
const MID = "44444444-4444-4444-8444-444444444444";
const QUESTION = "Năm nay công việc của tôi thế nào?";
const SSE =
  'event: meta\ndata: {"follow_ups_left":2}\n\nevent: delta\ndata: {"text":"Lão Thiên Cơ luận: ấn 印. "}\n\nevent: done\ndata: {"seq":0}\n\n';
const ACCOUNT = {
  kind: "account",
  accountId: "a",
  emailMasked: "x",
  token: "acc-token",
  isAdmin: false,
  entitlements: { credits: 1, pass: null, features: [] },
};

type Call = { url: string; init: RequestInit };
let calls: Call[];
let next: () => Response;
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/problem+json" } });
const sse = () =>
  new Response(new TextEncoder().encode(SSE), { status: 200, headers: { "content-type": "text/event-stream" } });

const body = (over: Record<string, unknown> = {}) => ({
  reading_id: RID,
  kind: "FREE",
  topic: "SuNghiep",
  question: QUESTION,
  ai_consent: true,
  ...over,
});
function post(path: string, b: unknown, headers: Record<string, string> = { origin: ORIGIN }, signal?: AbortSignal): Request {
  return new Request(`${ORIGIN}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(b),
    signal,
  });
}
const askReq = (b: unknown = body(), h?: Record<string, string>, s?: AbortSignal) => post("/api/qa/readings", b, h, s);
const ctx = (id: string) => ({ params: Promise.resolve({ id }) });
const logged = (out: { mock: { calls: unknown[][] } }) => out.mock.calls.map((c) => String(c[0])).join("");

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  calls = [];
  next = sse;
  jar.values.clear();
  jar.sets.length = 0;
  session.ensureSession.mockReset().mockResolvedValue("guest-token");
  session.getViewer.mockReset().mockResolvedValue(ACCOUNT);
  session.readSessionToken.mockReset().mockResolvedValue("acc-token");
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return next();
    }),
  );
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  out.mockRestore();
});

describe("POST /api/qa/readings", () => {
  it("answers 403 to a foreign Origin and calls nothing", async () => {
    const res = await ask(askReq(body(), { origin: "https://evil.example" }));
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ reason: "forbidden" });
    expect(calls).toEqual([]);
    expect(session.ensureSession).not.toHaveBeenCalled();
  });

  it.each([
    ["reading_id", { reading_id: "nope" }],
    ["kind", { kind: "GOLD" }],
    ["topic", { topic: "Astro" }],
    ["question", { question: "ngắn" }],
    ["question", { question: "x".repeat(501) }],
    ["question", { question: 42 }],
  ])("answers 400 invalid for a bad %s", async (field, over) => {
    const res = await ask(askReq(body(over)));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "invalid", field });
    expect(calls).toEqual([]);
  });

  it("answers 400 invalid for an unreadable body", async () => {
    const res = await ask(
      new Request(`${ORIGIN}/api/qa/readings`, { method: "POST", headers: { origin: ORIGIN }, body: "{nope" }),
    );
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "invalid", field: "body" });
  });

  it("requires AI consent: 400 without the cookie and without ai_consent, and calls nothing", async () => {
    const res = await ask(askReq(body({ ai_consent: undefined })));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "ai_consent_required" });
    expect(calls).toEqual([]);
    expect(session.ensureSession).not.toHaveBeenCalled();
    expect(jar.sets).toEqual([]);
  });

  it("sets the consent cookie when ai_consent is true", async () => {
    const res = await ask(askReq());
    expect(res.status).toBe(200);
    expect(jar.sets).toHaveLength(1);
    const [name, value, opts] = jar.sets[0] as [string, string, Record<string, unknown>];
    expect([name, value]).toEqual(["tv_ai_ok", "1"]);
    expect(opts).toMatchObject({ httpOnly: true, sameSite: "lax", path: "/", maxAge: 365 * 86400 });
  });

  it("accepts a returning visitor with the cookie, without setting it again", async () => {
    jar.values.set("tv_ai_ok", "1");
    const res = await ask(askReq(body({ ai_consent: undefined })));
    expect(res.status).toBe(200);
    expect(jar.sets).toEqual([]);
  });

  it("pipes the upstream SSE with the exact headers and the same bytes", async () => {
    const res = await ask(askReq());
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("text/event-stream; charset=utf-8");
    expect(res.headers.get("cache-control")).toBe("no-cache, no-transform");
    expect(res.headers.get("x-accel-buffering")).toBe("no");
    expect(Array.from(new Uint8Array(await res.arrayBuffer()))).toEqual(Array.from(new TextEncoder().encode(SSE)));
  });

  it("sends the session bearer and lang vi, never ai_consent, to reading-tuvi", async () => {
    await ask(askReq());
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://localhost:8083/tuvi/qa/readings");
    expect(new Headers(calls[0].init.headers).get("authorization")).toBe("Bearer guest-token");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({
      reading_id: RID,
      kind: "FREE",
      topic: "SuNghiep",
      question: QUESTION,
      lang: "vi",
    });
  });

  it("aborts the upstream fetch when the client disconnects", async () => {
    const ac = new AbortController();
    await ask(askReq(body(), undefined, ac.signal));
    const upstream = calls[0].init.signal as AbortSignal;
    expect(upstream.aborted).toBe(false);
    ac.abort();
    expect(upstream.aborted).toBe(true);
  });

  it.each([
    [409, "no_credits"],
    [409, "free_quota"],
    [409, "reading_in_progress"],
    [409, "reading_conflict"],
    [403, "account_required"],
  ])("maps upstream %i %s to the same status and reason", async (status, reason) => {
    next = () => json(status, { status, reason });
    const res = await ask(askReq());
    expect(res.status).toBe(status);
    expect(await res.json()).toEqual({ reason });
    expect(logged(out)).not.toContain(QUESTION);
  });

  it("maps an upstream 401 to unauthenticated, and logs it without the question", async () => {
    next = () => json(401, { status: 401 });
    const res = await ask(askReq());
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ reason: "unauthenticated" });
    expect(logged(out)).toContain("upstream refused request");
    expect(logged(out)).not.toContain(QUESTION);
  });

  it("maps an upstream 400 to invalid and logs it", async () => {
    next = () => json(400, { status: 400, errors: [{ field: "question", message: "size" }] });
    const res = await ask(askReq());
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "invalid", field: "question" });
    expect(logged(out)).toContain("upstream refused request");
  });

  it("answers 503 unavailable when reading-tuvi is down or fails", async () => {
    next = () => json(503, { status: 503 });
    const res = await ask(askReq());
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ reason: "unavailable" });
  });

  it("answers 503 unavailable when identity cannot create the guest session", async () => {
    const { UpstreamError } = await import("@/lib/http");
    session.ensureSession.mockRejectedValue(new UpstreamError());
    const res = await ask(askReq());
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ reason: "unavailable" });
  });
});

describe("POST /api/qa/readings/[id]/follow-ups", () => {
  const fu = (
    b: unknown = { message_id: MID, question: QUESTION },
    h?: Record<string, string>,
    id = RID,
  ) => followUp(post(`/api/qa/readings/${id}/follow-ups`, b, h), ctx(id));

  it("answers 403 to a foreign Origin", async () => {
    const res = await fu(undefined, { origin: "https://evil.example" });
    expect(res.status).toBe(403);
    expect(calls).toEqual([]);
  });

  it("needs an account: 401 for anon, 403 account_required for a guest; nothing is called", async () => {
    session.getViewer.mockResolvedValue({ kind: "anon" });
    let res = await fu();
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ reason: "unauthenticated" });
    session.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    res = await fu();
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ reason: "account_required" });
    expect(calls).toEqual([]);
  });

  it("validates the reading id, message_id and question", async () => {
    let res = await fu(undefined, undefined, "not-a-uuid");
    expect(res.status).toBe(404);
    res = await fu({ message_id: "x", question: QUESTION });
    expect(await res.json()).toEqual({ reason: "invalid", field: "message_id" });
    res = await fu({ message_id: MID, question: "ngắn" });
    expect(await res.json()).toEqual({ reason: "invalid", field: "question" });
    expect(calls).toEqual([]);
  });

  it("pipes the follow-up stream with the account bearer", async () => {
    const res = await fu();
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("text/event-stream; charset=utf-8");
    expect(await res.text()).toBe(SSE);
    expect(calls[0].url).toBe(`http://localhost:8083/tuvi/qa/readings/${RID}/follow-ups`);
    expect(new Headers(calls[0].init.headers).get("authorization")).toBe("Bearer acc-token");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ message_id: MID, question: QUESTION });
  });

  it("passes upstream refusals through", async () => {
    next = () => json(409, { status: 409, reason: "follow_ups_exhausted" });
    const res = await fu();
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ reason: "follow_ups_exhausted" });
  });
});

describe("GET /api/qa/readings/[id]", () => {
  const get = (id = RID) => status(new Request(`${ORIGIN}/api/qa/readings/${id}`), ctx(id));
  const READING = {
    reading_id: RID,
    kind: "PAID",
    topic: "SuNghiep",
    status: "RETRYING",
    created_at: "2026-11-25T03:00:00Z",
    retry_until: null,
    follow_ups_left: 2,
    notice: null,
    disclaimer: "d",
    messages: [{ seq: 0, question: QUESTION, answer: null, created_at: "2026-11-25T03:00:00Z" }],
  };

  it("returns only status and follow_ups_left (never the question)", async () => {
    next = () => new Response(JSON.stringify(READING), { status: 200, headers: { "content-type": "application/json" } });
    const res = await get();
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await res.json()).toEqual({ status: "RETRYING", follow_ups_left: 2 });
  });

  it("answers 401 without a session, 404 for a malformed or unknown id, 503 when down", async () => {
    session.readSessionToken.mockResolvedValue(undefined);
    expect((await get()).status).toBe(401);
    session.readSessionToken.mockResolvedValue("acc-token");
    expect((await get("../x")).status).toBe(404);
    next = () => json(404, { status: 404 });
    expect((await get()).status).toBe(404);
    next = () => json(503, { status: 503 });
    const res = await get();
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ reason: "unavailable" });
  });
});
