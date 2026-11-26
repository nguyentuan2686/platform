import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const getViewer = vi.hoisted(() => vi.fn());
vi.mock("@/lib/session", () => ({ getViewer: () => getViewer() }));

import { POST } from "@/app/api/dev/sepay/route";

const ORIGIN = "http://localhost:3001";
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
const order = (status = "PENDING") => ({
  order_id: ORD,
  code: "TV7K3M9P",
  status,
  amount: "29000.00",
  currency: "VND",
  provider: "SEPAY",
  product_code: "CREDIT_1",
  created_at: "2026-11-25T03:00:00Z",
  expires_at: "2026-11-25T03:30:00Z",
});

type Call = { url: string; method: string; headers: Headers; raw?: string };
let calls: Call[];
let orderRes: () => Response;
let hookRes: () => Response;
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function req(headers: Record<string, string> = { origin: ORIGIN }, body: unknown = { order_id: ORD }): Request {
  return new Request(`${ORIGIN}/api/dev/sepay`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}
const webhooks = () => calls.filter((c) => c.url.endsWith("/webhooks/sepay"));

let out: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  calls = [];
  orderRes = () => json(200, order());
  hookRes = () => json(200, { success: true });
  process.env.DEV_PAYMENT_SIMULATOR = "1";
  process.env.DEPLOY_ENV = "local";
  process.env.SEPAY_WEBHOOK_SECRET = "local-test-sepay-secret";
  getViewer.mockReset();
  getViewer.mockResolvedValue(ACCOUNT);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: String(init.method), headers: new Headers(init.headers), raw: init.body ? String(init.body) : undefined });
      return url.endsWith("/webhooks/sepay") ? hookRes() : orderRes();
    }),
  );
  out = vi.spyOn(process.stdout, "write").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  out.mockRestore();
  for (const k of ["DEV_PAYMENT_SIMULATOR", "DEPLOY_ENV", "SEPAY_WEBHOOK_SECRET"]) delete process.env[k];
});

describe("POST /api/dev/sepay", () => {
  it("answers 404 when the simulator flag is off", async () => {
    delete process.env.DEV_PAYMENT_SIMULATOR;
    expect((await POST(req())).status).toBe(404);
    process.env.DEV_PAYMENT_SIMULATOR = "0";
    expect((await POST(req())).status).toBe(404);
    expect(calls).toEqual([]);
    expect(getViewer).not.toHaveBeenCalled();
  });

  it("answers 404 when the flag is 1 but DEPLOY_ENV is prod", async () => {
    process.env.DEPLOY_ENV = "prod";
    expect((await POST(req())).status).toBe(404);
    expect(calls).toEqual([]);
  });

  it("answers 403 to a foreign Origin (or no same-origin evidence)", async () => {
    let res = await POST(req({ origin: "https://evil.example" }));
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ reason: "forbidden" });
    res = await POST(req({ "sec-fetch-site": "cross-site" }));
    expect(res.status).toBe(403);
    expect(calls).toEqual([]);
  });

  it("answers 401 without an account session", async () => {
    getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    expect((await POST(req())).status).toBe(401);
    expect(calls).toEqual([]);
  });

  it("answers 404 for another account's order and posts no webhook", async () => {
    orderRes = () => json(404, { status: 404 });
    expect((await POST(req())).status).toBe(404);
    expect(calls.map((c) => c.url)).toEqual([`http://localhost:8085/orders/${ORD}?account_id=${ACC}`]);
    expect(webhooks()).toHaveLength(0);
  });

  it("answers 404 for a malformed body", async () => {
    expect((await POST(req(undefined, { order_id: "../x" }))).status).toBe(404);
    expect((await POST(req(undefined, "nope"))).status).toBe(404);
    expect(calls).toEqual([]);
  });

  it("answers 409 for an order that is no longer payable", async () => {
    orderRes = () => json(200, order("PAID"));
    const res = await POST(req());
    expect(res.status).toBe(409);
    expect(webhooks()).toHaveLength(0);
  });

  it.each(["PENDING", "EXPIRED"])("204 posts exactly one authenticated webhook for a %s order", async (status) => {
    orderRes = () => json(200, order(status));
    const res = await POST(req());
    expect(res.status).toBe(204);
    expect(webhooks()).toHaveLength(1);
    const hook = webhooks()[0];
    expect(hook.method).toBe("POST");
    expect(hook.headers.get("authorization")).toBe("Apikey local-test-sepay-secret");
    const body = JSON.parse(hook.raw ?? "{}");
    expect(body).toMatchObject({ content: "TV7K3M9P CHUYEN KHOAN", transferType: "in", transferAmount: 29000 });
  });

  it("logs a webhook refusal (secret differs from payment-svc) and answers 503", async () => {
    hookRes = () => json(401, { status: 401 });
    const res = await POST(req());
    expect(res.status).toBe(503);
    const lines = out.mock.calls.map((c: unknown[]) => JSON.parse(String(c[0])));
    expect(lines).toEqual([expect.objectContaining({ level: "error", upstream: "payment-svc", status: "401", part: "dev-sepay" })]);
    const raw = JSON.stringify(lines);
    expect(raw).not.toContain("local-test-sepay-secret");
    expect(raw).not.toContain("TV7K3M9P");
  });

  it("never logs the transfer content or the secret", async () => {
    await POST(req());
    const logged = out.mock.calls.map((c: unknown[]) => String(c[0])).join("");
    expect(logged).not.toContain("TV7K3M9P");
    expect(logged).not.toContain("local-test-sepay-secret");
  });
});
