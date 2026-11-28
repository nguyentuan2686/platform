import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const session = vi.hoisted(() => ({ ensureSession: vi.fn(), readSessionToken: vi.fn() }));
vi.mock("@/lib/session", () => session);
const hdrs = vi.hoisted(() => ({ values: new Map<string, string>() }));
vi.mock("next/headers", () => ({ headers: async () => ({ get: (n: string) => hdrs.values.get(n) ?? null }) }));

import { POST } from "@/app/api/xem-so/route";
import { GET } from "@/app/api/xem-so/quota/route";

const ORIGIN = "http://localhost:3001";
const NUMBER = "0912345678";
const RESULT = {
  score: 7,
  band: "kha_hop",
  bandLabel: "Khá hợp",
  menh: { canChi: "Canh Ngọ", napAm: "Lộ Bàng Thổ", element: "Thổ" },
  cungPhi: { name: "Khảm", group: "Đông tứ mệnh" },
  criteria: [],
  reading: ["Lời kết."],
  quota: { limit: 2, used: 1, remaining: 1 },
};

type Call = { url: string; init: RequestInit };
let calls: Call[];
let responses: (() => Response)[];
const json = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": type } });

const body = (over: Record<string, unknown> = {}) => ({
  kind: "sim",
  number: NUMBER,
  birthYear: 1990,
  beforeTet: false,
  gender: "male",
  ...over,
});
const post = (b: unknown, headers: Record<string, string> = { origin: ORIGIN }) =>
  new Request(`${ORIGIN}/api/xem-so`, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(b) });

let out: ReturnType<typeof vi.spyOn>;
let err: ReturnType<typeof vi.spyOn>;
const logged = () => [...out.mock.calls, ...err.mock.calls].map((c) => String(c[0])).join("");

beforeEach(() => {
  calls = [];
  responses = [() => json(200, RESULT)];
  hdrs.values.clear();
  session.ensureSession.mockReset().mockResolvedValue("guest-token");
  session.readSessionToken.mockReset().mockResolvedValue("guest-token");
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      const next = responses.length > 1 ? responses.shift()! : responses[0]!;
      return next();
    }),
  );
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
  err = vi.spyOn(process.stderr, "write").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("POST /api/xem-so", () => {
  it("refuses a cross-site post with 403", async () => {
    const res = await POST(post(body(), { origin: "https://evil.example" }));
    expect(res.status).toBe(403);
    expect(calls).toHaveLength(0);
  });

  it.each([
    ["missing gender", { gender: undefined }, "gender"],
    ["plate without vehicle", { kind: "plate", number: "30A12345" }, "vehicle"],
    ["non-integer year", { birthYear: 1990.5 }, "birthYear"],
    ["year as text", { birthYear: "1990" }, "birthYear"],
    ["unknown kind", { kind: "phone" }, "kind"],
    ["empty number", { number: " " }, "number"],
  ])("answers 400 for %s without calling upstream", async (_, over, field) => {
    const res = await POST(post(body(over)));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "invalid", field });
    expect(calls).toHaveLength(0);
  });

  it("posts the number in the body only, with the client IP, and answers no-store", async () => {
    hdrs.values.set("cf-connecting-ip", "203.0.113.7");
    const res = await POST(post(body()));
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await res.json()).toEqual(RESULT);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toMatch(/\/tuvi\/number-check$/);
    expect(calls[0]!.url).not.toContain(NUMBER);
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual(body());
    const h = calls[0]!.init.headers as Headers;
    expect(h.get("x-client-ip")).toBe("203.0.113.7");
    expect(h.get("authorization")).toBe("Bearer guest-token");
  });

  it("passes the vehicle only for a plate", async () => {
    await POST(post(body({ kind: "plate", vehicle: "motorbike", number: "29B112345" })));
    expect(JSON.parse(String(calls[0]!.init.body)).vehicle).toBe("motorbike");
    await POST(post(body({ vehicle: "car" })));
    expect(JSON.parse(String(calls[1]!.init.body)).vehicle).toBeUndefined();
  });

  it("maps 409 number_quota to 409 with the quota and logs nothing", async () => {
    responses = [
      () => json(409, { reason: "number_quota", quota: { limit: 2, used: 2, remaining: 0 } }, "application/problem+json"),
      () => json(200, { limit: 2, used: 2, remaining: 0 }),
    ];
    const res = await POST(post(body()));
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ reason: "number_quota", quota: { limit: 2, used: 2, remaining: 0 } });
    expect(logged()).toBe("");
  });

  it("maps an upstream invalid number to 400 quietly", async () => {
    responses = [
      () => json(400, { errors: [{ field: "number", message: "number is invalid" }], reason: "invalid_number" }, "application/problem+json"),
    ];
    const res = await POST(post(body({ number: "0912" })));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ reason: "invalid", field: "number" });
    expect(logged()).toBe("");
  });

  it("answers 503 when reading-tuvi fails and never logs the number", async () => {
    responses = [() => json(500, {})];
    const res = await POST(post(body()));
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ reason: "unavailable" });
    expect(logged()).not.toContain(NUMBER);
    expect(logged()).not.toContain("1990");
  });
});

describe("GET /api/xem-so/quota", () => {
  it("is 204 without a session and creates no guest", async () => {
    session.readSessionToken.mockResolvedValue(undefined);
    const res = await GET();
    expect(res.status).toBe(204);
    expect(calls).toHaveLength(0);
    expect(session.ensureSession).not.toHaveBeenCalled();
  });

  it("forwards the quota", async () => {
    responses = [() => json(200, { limit: 2, used: 1, remaining: 1 })];
    const res = await GET();
    expect(await res.json()).toEqual({ limit: 2, used: 1, remaining: 1 });
    expect(calls[0]!.url).toMatch(/\/tuvi\/number-check\/quota$/);
  });
});
