import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const getViewer = vi.hoisted(() => vi.fn());
vi.mock("@/lib/session", () => ({ getViewer: () => getViewer() }));

import { GET } from "@/app/api/orders/[id]/route";

const ACC = "11111111-1111-1111-1111-111111111111";
const ORD = "22222222-2222-2222-2222-222222222222";
const ACCOUNT = {
  kind: "account",
  accountId: ACC,
  emailMasked: "a***@e***.com",
  token: "acc-token",
  isAdmin: false,
  entitlements: { credits: 1, pass: null, features: [] },
};
const ORDER = {
  order_id: ORD,
  code: "TV7K3M9P",
  status: "PAID",
  amount: "29000.00",
  currency: "VND",
  provider: "SEPAY",
  product_code: "CREDIT_1",
  created_at: "2026-11-25T03:00:00Z",
  expires_at: "2026-11-25T03:30:00Z",
  paid_at: "2026-11-25T03:01:00Z",
};

let urls: string[];
let next: () => Response;
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const call = (id = ORD) =>
  GET(new Request(`http://localhost:3001/api/orders/${id}`), { params: Promise.resolve({ id }) });

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  urls = [];
  next = () => json(200, ORDER);
  getViewer.mockReset();
  getViewer.mockResolvedValue(ACCOUNT);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      urls.push(url);
      return next();
    }),
  );
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  out.mockRestore();
});

describe("GET /api/orders/[id]", () => {
  it("answers 401 without an account session and calls nothing", async () => {
    getViewer.mockResolvedValue({ kind: "anon" });
    let res = await call();
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ reason: "unauthenticated" });
    getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    res = await call();
    expect(res.status).toBe(401);
    expect(urls).toEqual([]);
  });

  it("returns only the status (and paid_at) of the viewer's own order", async () => {
    const res = await call();
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await res.json()).toEqual({ status: "PAID", paid_at: "2026-11-25T03:01:00Z" });
    expect(urls).toEqual([`http://localhost:8085/orders/${ORD}?account_id=${ACC}`]);
  });

  it("omits paid_at while unpaid", async () => {
    next = () => json(200, { ...ORDER, status: "PENDING", paid_at: undefined });
    expect(await (await call()).json()).toEqual({ status: "PENDING" });
  });

  it("passes a 404 through (unknown or another account's order)", async () => {
    next = () => json(404, { status: 404 });
    const res = await call();
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ reason: "not_found" });
  });

  it("answers 404 for a malformed id without calling payment-svc", async () => {
    const res = await call("../admin");
    expect(res.status).toBe(404);
    expect(urls).toEqual([]);
  });

  it("answers 503 when payment-svc is down", async () => {
    next = () => json(503, { status: 503 });
    const res = await call();
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ reason: "unavailable" });
  });
});
